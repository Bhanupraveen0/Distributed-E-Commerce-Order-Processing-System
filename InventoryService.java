package com.ecommerce;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryService implements Runnable {
    public static final int PORT = 6001;
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    public InventoryService() {
        stock.put("LAPTOP-1001", 15);
        stock.put("PHONE-2002", 25);
        stock.put("HEADSET-3003", 40);
        stock.put("WATCH-4004", 20);
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[InventoryService] Listening on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "InventoryWorker").start();
            }
        } catch (Exception e) {
            System.err.println("[InventoryService] Error: " + e.getMessage());
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

            String[] parts = request.split(":");
            String command = parts[0];

            if ("CHECK_STOCK".equals(command)) {
                if (parts.length < 3) {
                    out.println("FAIL:INVALID_REQUEST");
                    return;
                }

                String productId = parts[1];
                int quantityRequested;
                try {
                    quantityRequested = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    out.println("FAIL:INVALID_QUANTITY");
                    return;
                }

                synchronized (this) {
                    Integer available = stock.get(productId);
                    if (available == null) {
                        out.println("FAIL:PRODUCT_NOT_FOUND");
                        return;
                    }

                    if (available >= quantityRequested) {
                        stock.put(productId, available - quantityRequested);
                        EventLogger.info("InventoryService", "Reserved " + quantityRequested + " of " + productId + " (Remaining: " + (available - quantityRequested) + ")");
                        out.println("OK:ITEM_AVAILABLE:" + productId + ":" + quantityRequested);
                    } else {
                        EventLogger.fail("InventoryService", "Out of stock for " + productId + " (Requested: " + quantityRequested + ", Available: " + available + ")");
                        out.println("FAIL:OUT_OF_STOCK:" + productId + ":" + available);
                    }
                }
            } else if ("RELEASE_STOCK".equals(command)) {
                if (parts.length < 3) {
                    out.println("FAIL:INVALID_REQUEST");
                    return;
                }

                String productId = parts[1];
                int quantityToRelease;
                try {
                    quantityToRelease = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    out.println("FAIL:INVALID_QUANTITY");
                    return;
                }

                synchronized (this) {
                    Integer current = stock.get(productId);
                    if (current != null) {
                        stock.put(productId, current + quantityToRelease);
                        EventLogger.compensation("InventoryService", "[COMPENSATION] Restored " + quantityToRelease + " of " + productId + " (New Stock: " + (current + quantityToRelease) + ")");
                        out.println("OK:STOCK_RELEASED:" + productId + ":" + (current + quantityToRelease));
                    } else {
                        out.println("FAIL:PRODUCT_NOT_FOUND");
                    }
                }
            } else if ("GET_STOCK".equals(command)) {
                if (parts.length < 2) {
                    out.println("FAIL:INVALID_REQUEST");
                    return;
                }
                String productId = parts[1];
                Integer current = stock.get(productId);
                if (current != null) {
                    out.println("OK:STOCK:" + productId + ":" + current);
                } else {
                    out.println("FAIL:PRODUCT_NOT_FOUND");
                }
            } else if ("RESET".equals(command)) {
                synchronized (this) {
                    stock.put("LAPTOP-1001", 15);
                    stock.put("PHONE-2002", 25);
                    stock.put("HEADSET-3003", 40);
                    stock.put("WATCH-4004", 20);
                    EventLogger.info("InventoryService", "Reset inventory stock levels to baseline.");
                    out.println("OK:RESET_SUCCESS");
                }
            } else {
                out.println("FAIL:UNKNOWN_COMMAND");
            }
        } catch (Exception e) {
            System.err.println("[InventoryService] Error: " + e.getMessage());
        }
    }
}
