package com.ecommerce;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class ShippingService implements Runnable {
    public static final int PORT = 6003;
    private final AtomicInteger trackingCounter = new AtomicInteger(1000);

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[ShippingService] Listening on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "ShippingWorker").start();
            }
        } catch (Exception e) {
            System.err.println("[ShippingService] Error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String request = in.readLine();
            if (request == null) {
                return;
            }

            String[] parts = request.split(":", 4);
            if (parts.length < 4 || !"SHIP".equals(parts[0])) {
                out.println("FAIL:INVALID_SHIPPING_REQUEST");
                return;
            }

            String orderId = parts[1];
            String productId = parts[2];
            String address = parts[3];

            if (address == null || address.trim().isEmpty() || address.trim().length() < 5) {
                EventLogger.fail("ShippingService", "Shipping rejected for " + orderId + ": Address too short or invalid ('" + address + "')");
                out.println("FAIL:ADDRESS_INVALID");
                return;
            }

            String trackingId = "TRK-" + trackingCounter.incrementAndGet();
            EventLogger.success("ShippingService", "Dispatched " + orderId + " (" + productId + ") to '" + address + "' -> Tracking ID: " + trackingId);
            out.println("OK:SHIPPED:" + orderId + ":" + productId + ":" + trackingId);
        } catch (Exception e) {
            System.err.println("[ShippingService] Error handling client: " + e.getMessage());
        }
    }
}
