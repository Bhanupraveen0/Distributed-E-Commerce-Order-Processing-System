package com.ecommerce;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class OrderCoordinator implements Runnable {
    public static final int PORT = 5000;

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[OrderCoordinator] Listening on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> processOrder(socket), "OrderCoordinatorWorker").start();
            }
        } catch (Exception e) {
            System.err.println("[OrderCoordinator] Error: " + e.getMessage());
        }
    }

    private void processOrder(Socket clientSocket) {
        try (clientSocket;
             BufferedReader clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter clientOut = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request = clientIn.readLine();
            if (request == null) {
                return;
            }

            String[] orderParts = request.split(":", 6);
            if (orderParts.length < 6 || !"PLACE_ORDER".equals(orderParts[0])) {
                clientOut.println("ERROR:INVALID_ORDER_REQUEST");
                return;
            }

            String customerId = orderParts[1];
            String productId = orderParts[2];
            int quantity;
            double amount;
            String address;

            try {
                quantity = Integer.parseInt(orderParts[3]);
                amount = Double.parseDouble(orderParts[4]);
                address = orderParts[5];
            } catch (NumberFormatException e) {
                clientOut.println("ERROR:INVALID_ORDER_DATA");
                return;
            }

            String orderId = "ORD-" + System.currentTimeMillis();
            EventLogger.info("OrderCoordinator", "Initiating Order " + orderId + " [Customer: " + customerId
                    + ", Product: " + productId + ", Quantity: " + quantity + ", Amount: $" + amount + "]");

            // Step 1: Inventory Check and Stock Reservation
            EventLogger.info("OrderCoordinator", "Step 1: Requesting stock reservation from InventoryService...");
            String inventoryResponse = sendToService("localhost", InventoryService.PORT, "CHECK_STOCK:" + productId + ":" + quantity);
            if (!inventoryResponse.startsWith("OK:")) {
                EventLogger.fail("OrderCoordinator", "Step 1 Failed: " + inventoryResponse);
                clientOut.println("ORDER_FAILED:INVENTORY:" + inventoryResponse);
                return;
            }
            EventLogger.success("OrderCoordinator", "Step 1 Succeeded: Stock reserved.");

            // Step 2: Payment Processing
            EventLogger.info("OrderCoordinator", "Step 2: Requesting payment processing from PaymentService...");
            String paymentResponse = sendToService("localhost", PaymentService.PORT, "PAY:" + customerId + ":" + amount + ":" + orderId);
            if (!paymentResponse.startsWith("OK:")) {
                EventLogger.fail("OrderCoordinator", "Step 2 Failed: " + paymentResponse);
                EventLogger.compensation("OrderCoordinator", "[SAGA ROLLBACK] Triggering compensation: Releasing reserved stock...");
                // Compensate Step 1: Release reserved stock
                String invRollback = sendToService("localhost", InventoryService.PORT, "RELEASE_STOCK:" + productId + ":" + quantity);
                EventLogger.info("OrderCoordinator", "Inventory rollback result: " + invRollback);
                clientOut.println("ORDER_FAILED:PAYMENT:" + paymentResponse);
                return;
            }
            EventLogger.success("OrderCoordinator", "Step 2 Succeeded: Payment approved.");

            // Step 3: Shipping Dispatch
            EventLogger.info("OrderCoordinator", "Step 3: Requesting shipping dispatch from ShippingService...");
            String shippingResponse = sendToService("localhost", ShippingService.PORT, "SHIP:" + orderId + ":" + productId + ":" + address);
            if (!shippingResponse.startsWith("OK:")) {
                EventLogger.fail("OrderCoordinator", "Step 3 Failed: " + shippingResponse);
                EventLogger.compensation("OrderCoordinator", "[SAGA ROLLBACK] Triggering compensation: Refunding payment & releasing stock...");
                // Compensate Step 2: Refund payment
                String payRollback = sendToService("localhost", PaymentService.PORT, "REFUND:" + customerId + ":" + amount + ":" + orderId);
                EventLogger.info("OrderCoordinator", "Payment refund result: " + payRollback);
                // Compensate Step 1: Release reserved stock
                String invRollback = sendToService("localhost", InventoryService.PORT, "RELEASE_STOCK:" + productId + ":" + quantity);
                EventLogger.info("OrderCoordinator", "Inventory rollback result: " + invRollback);
                clientOut.println("ORDER_FAILED:SHIPPING:" + shippingResponse);
                return;
            }
            EventLogger.success("OrderCoordinator", "Step 3 Succeeded: Shipment dispatched.");

            // Parse trackingId: Response is OK:SHIPPED:<orderId>:<productId>:<trackingId>
            String[] shipTokens = shippingResponse.split(":");
            String trackingId = (shipTokens.length > 4) ? shipTokens[4] : shipTokens[shipTokens.length - 1];

            EventLogger.success("OrderCoordinator", "Order " + orderId + " fully CONFIRMED with Tracking ID: " + trackingId);
            clientOut.println("ORDER_CONFIRMED:" + orderId + ":" + trackingId + ":" + productId);
        } catch (IOException e) {
            System.err.println("[OrderCoordinator] Error processing order: " + e.getMessage());
        }
    }

    private String sendToService(String host, int port, String request) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                out.println(request);
                String response = in.readLine();
                return (response != null) ? response : "FAIL:EMPTY_RESPONSE";
            }
        } catch (IOException e) {
            return "FAIL:SERVICE_UNAVAILABLE:" + e.getMessage();
        }
    }
}
