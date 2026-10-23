package com.ecommerce;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaymentService implements Runnable {
    public static final int PORT = 6002;
    private final Map<String, Double> wallets = new ConcurrentHashMap<>();

    public PaymentService() {
        wallets.put("CUST-101", 5000.00);
        wallets.put("CUST-102", 2500.00);
        wallets.put("CUST-103", 7500.00);
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[PaymentService] Listening on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "PaymentWorker").start();
            }
        } catch (Exception e) {
            System.err.println("[PaymentService] Error: " + e.getMessage());
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

            if ("PAY".equals(command)) {
                if (parts.length < 4) {
                    out.println("FAIL:INVALID_PAYMENT_REQUEST");
                    return;
                }

                String customerId = parts[1];
                double amount;
                try {
                    amount = Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    out.println("FAIL:INVALID_AMOUNT");
                    return;
                }

                String orderId = parts[3];
                synchronized (this) {
                    Double balance = wallets.get(customerId);
                    if (balance == null) {
                        out.println("FAIL:CUSTOMER_NOT_FOUND");
                        return;
                    }

                    if (balance >= amount) {
                        wallets.put(customerId, balance - amount);
                        EventLogger.info("PaymentService", "Debited $" + amount + " from " + customerId + " for " + orderId + " (Remaining Balance: $" + (balance - amount) + ")");
                        out.println("OK:PAYMENT_APPROVED:" + orderId + ":" + amount);
                    } else {
                        EventLogger.fail("PaymentService", "Payment failed for " + customerId + ": Insufficient funds (Required: $" + amount + ", Balance: $" + balance + ")");
                        out.println("FAIL:INSUFFICIENT_FUNDS:" + customerId + ":" + balance);
                    }
                }
            } else if ("REFUND".equals(command)) {
                if (parts.length < 4) {
                    out.println("FAIL:INVALID_REFUND_REQUEST");
                    return;
                }

                String customerId = parts[1];
                double amount;
                try {
                    amount = Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    out.println("FAIL:INVALID_AMOUNT");
                    return;
                }
                String orderId = parts[3];

                synchronized (this) {
                    Double balance = wallets.get(customerId);
                    if (balance != null) {
                        wallets.put(customerId, balance + amount);
                        EventLogger.compensation("PaymentService", "[COMPENSATION] Refunded $" + amount + " to " + customerId + " for " + orderId + " (Restored Balance: $" + (balance + amount) + ")");
                        out.println("OK:REFUNDED:" + orderId + ":" + customerId + ":" + (balance + amount));
                    } else {
                        out.println("FAIL:CUSTOMER_NOT_FOUND");
                    }
                }
            } else if ("GET_BALANCE".equals(command)) {
                if (parts.length < 2) {
                    out.println("FAIL:INVALID_REQUEST");
                    return;
                }
                String customerId = parts[1];
                Double balance = wallets.get(customerId);
                if (balance != null) {
                    out.println("OK:BALANCE:" + customerId + ":" + balance);
                } else {
                    out.println("FAIL:CUSTOMER_NOT_FOUND");
                }
            } else if ("RESET".equals(command)) {
                synchronized (this) {
                    wallets.put("CUST-101", 5000.00);
                    wallets.put("CUST-102", 2500.00);
                    wallets.put("CUST-103", 7500.00);
                    EventLogger.info("PaymentService", "Reset digital wallets to baseline.");
                    out.println("OK:RESET_SUCCESS");
                }
            } else {
                out.println("FAIL:UNKNOWN_COMMAND");
            }
        } catch (Exception e) {
            System.err.println("[PaymentService] Error handling client: " + e.getMessage());
        }
    }
}
