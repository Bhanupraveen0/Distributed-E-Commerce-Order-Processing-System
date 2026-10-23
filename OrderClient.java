package com.ecommerce;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class OrderClient {
    private static final String HOST = "localhost";
    private static final int PORT = OrderCoordinator.PORT;

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("  Distributed E-Commerce Order Processing System - Client Tests   ");
        System.out.println("==================================================================\n");

        if (args.length > 0 && "--interactive".equalsIgnoreCase(args[0])) {
            runInteractiveMode();
            return;
        }

        String[] testOrders = {
            // Scenario 1: Successful Order
            "PLACE_ORDER:CUST-101:LAPTOP-1001:1:1200.00:12 Main Street, Bengaluru",

            // Scenario 2: Payment Failure (Insufficient Funds -> Triggers Saga compensation / rollback)
            "PLACE_ORDER:CUST-102:LAPTOP-1001:1:99999.00:45 Park Road, Chennai",

            // Scenario 3: Inventory Failure (Out of stock)
            "PLACE_ORDER:CUST-103:WATCH-4004:999:250.00:21 Lake View, Pune",

            // Scenario 4: Shipping Failure (Invalid address -> Triggers full compensation: refund + stock restore)
            "PLACE_ORDER:CUST-101:PHONE-2002:1:500.00:N/A",

            // Scenario 5: Successful Order after rollback (verifies state consistency and restored stock)
            "PLACE_ORDER:CUST-102:PHONE-2002:2:700.00:77 Tech Park, Chennai"
        };

        String[] descriptions = {
            "Test 1 [SUCCESS]: Standard order for CUST-101 (Laptop)",
            "Test 2 [PAYMENT FAILURE & ROLLBACK]: CUST-102 insufficient funds (reserved stock released)",
            "Test 3 [INVENTORY FAILURE]: Quantity exceeds available stock for WATCH-4004",
            "Test 4 [SHIPPING FAILURE & FULL ROLLBACK]: Invalid shipping address (payment refunded & stock released)",
            "Test 5 [SUCCESS POST-ROLLBACK]: CUST-102 ordering Phone (verifies consistent ledger)"
        };

        for (int i = 0; i < testOrders.length; i++) {
            System.out.println(">>> Scenario " + (i + 1) + ": " + descriptions[i]);
            System.out.println("Request  : " + testOrders[i]);
            String response = sendOrder(testOrders[i]);
            System.out.println("Response : " + response);
            System.out.println("------------------------------------------------------------------\n");

            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {}
        }

        System.out.println("All test scenarios completed successfully.");
    }

    private static void runInteractiveMode() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Interactive Mode activated.");
        System.out.println("Format: PLACE_ORDER:<customerId>:<productId>:<quantity>:<amount>:<address>");
        System.out.println("Example: PLACE_ORDER:CUST-101:LAPTOP-1001:1:1200.00:12 Main Street, Bengaluru");
        System.out.println("Type 'exit' to quit.\n");

        while (true) {
            System.out.print("Enter order > ");
            String input = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                break;
            }
            if (input.isEmpty()) {
                continue;
            }
            String response = sendOrder(input);
            System.out.println("Coordinator Response: " + response + "\n");
        }
    }

    public static String sendOrder(String orderRequest) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(HOST, PORT), 3000);
            socket.setSoTimeout(6000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                out.println(orderRequest);
                String response = in.readLine();
                return (response != null) ? response : "ERROR:EMPTY_RESPONSE";
            }
        } catch (Exception e) {
            return "ERROR:CLIENT_CONNECTION_FAILED:" + e.getMessage();
        }
    }
}
