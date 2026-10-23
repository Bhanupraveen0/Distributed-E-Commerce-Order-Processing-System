package com.ecommerce;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP Web Dashboard Server for the Distributed E-Commerce Order System.
 * Listens on port 8080 and provides an interactive web interface and REST endpoints.
 */
public class WebDashboardServer implements Runnable {
    public static final int HTTP_PORT = 8080;

    @Override
    public void run() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            server.createContext("/", new DashboardHandler());
            server.createContext("/api/state", new StateHandler());
            server.createContext("/api/order", new OrderHandler());
            server.createContext("/api/logs", new LogsHandler());
            server.createContext("/api/reset", new ResetHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            EventLogger.info("WebDashboard", "Web Dashboard active at http://localhost:" + HTTP_PORT);
        } catch (IOException e) {
            System.err.println("[WebDashboard] Failed to start HTTP server: " + e.getMessage());
        }
    }

    private static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            byte[] responseBytes = getDashboardHtml().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private static class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Query inventory stock
            int laptopStock = fetchStock("LAPTOP-1001");
            int phoneStock = fetchStock("PHONE-2002");
            int headsetStock = fetchStock("HEADSET-3003");
            int watchStock = fetchStock("WATCH-4004");

            // Query wallet balances
            double cust101 = fetchBalance("CUST-101");
            double cust102 = fetchBalance("CUST-102");
            double cust103 = fetchBalance("CUST-103");

            // Check ports
            boolean coordOnline = isPortOpen(OrderCoordinator.PORT);
            boolean invOnline = isPortOpen(InventoryService.PORT);
            boolean payOnline = isPortOpen(PaymentService.PORT);
            boolean shipOnline = isPortOpen(ShippingService.PORT);

            String json = String.format(
                "{" +
                "\"services\":[" +
                "{\"name\":\"OrderCoordinator\",\"port\":%d,\"status\":\"%s\"}," +
                "{\"name\":\"InventoryService\",\"port\":%d,\"status\":\"%s\"}," +
                "{\"name\":\"PaymentService\",\"port\":%d,\"status\":\"%s\"}," +
                "{\"name\":\"ShippingService\",\"port\":%d,\"status\":\"%s\"}" +
                "]," +
                "\"inventory\":[" +
                "{\"id\":\"LAPTOP-1001\",\"name\":\"Pro Laptop 15'\",\"stock\":%d,\"initial\":15,\"price\":1200.0}," +
                "{\"id\":\"PHONE-2002\",\"name\":\"5G Flagship Phone\",\"stock\":%d,\"initial\":25,\"price\":500.0}," +
                "{\"id\":\"HEADSET-3003\",\"name\":\"ANC Wireless Headset\",\"stock\":%d,\"initial\":40,\"price\":150.0}," +
                "{\"id\":\"WATCH-4004\",\"name\":\"Fitness Smartwatch\",\"stock\":%d,\"initial\":20,\"price\":250.0}" +
                "]," +
                "\"wallets\":[" +
                "{\"id\":\"CUST-101\",\"name\":\"Alice (Customer 101)\",\"balance\":%.2f,\"initial\":5000.0}," +
                "{\"id\":\"CUST-102\",\"name\":\"Bob (Customer 102)\",\"balance\":%.2f,\"initial\":2500.0}," +
                "{\"id\":\"CUST-103\",\"name\":\"Charlie (Customer 103)\",\"balance\":%.2f,\"initial\":7500.0}" +
                "]" +
                "}",
                OrderCoordinator.PORT, coordOnline ? "ONLINE" : "OFFLINE",
                InventoryService.PORT, invOnline ? "ONLINE" : "OFFLINE",
                PaymentService.PORT, payOnline ? "ONLINE" : "OFFLINE",
                ShippingService.PORT, shipOnline ? "ONLINE" : "OFFLINE",
                laptopStock, phoneStock, headsetStock, watchStock,
                cust101, cust102, cust103
            );

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }

            String customerId = extractJsonField(body, "customerId");
            String productId = extractJsonField(body, "productId");
            String quantity = extractJsonField(body, "quantity");
            String amount = extractJsonField(body, "amount");
            String address = extractJsonField(body, "address");

            if (customerId == null || productId == null || quantity == null || amount == null || address == null) {
                String error = "{\"error\":\"Missing required order fields\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length());
                exchange.getResponseBody().write(error.getBytes(StandardCharsets.UTF_8));
                return;
            }

            String orderCmd = "PLACE_ORDER:" + customerId + ":" + productId + ":" + quantity + ":" + amount + ":" + address;
            EventLogger.info("OrderClient", "Dispatching web order: " + orderCmd);

            String response = querySocket("localhost", OrderCoordinator.PORT, orderCmd);

            boolean success = response.startsWith("ORDER_CONFIRMED:");
            String orderId = "";
            String trackingId = "";
            String[] tokens = response.split(":");
            if (success && tokens.length >= 3) {
                orderId = tokens[1];
                trackingId = tokens[2];
            }

            String jsonResponse = String.format(
                "{\"success\":%b,\"raw\":\"%s\",\"orderId\":\"%s\",\"trackingId\":\"%s\",\"response\":\"%s\"}",
                success, escapeJson(response), escapeJson(orderId), escapeJson(trackingId), escapeJson(response)
            );

            byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            List<EventLogger.LogEntry> logs = EventLogger.getLogs();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < logs.size(); i++) {
                sb.append(logs.get(i).toJson());
                if (i < logs.size() - 1) sb.append(",");
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            querySocket("localhost", InventoryService.PORT, "RESET");
            querySocket("localhost", PaymentService.PORT, "RESET");
            EventLogger.info("System", "Demonstration state re-initialized to initial defaults.");

            String json = "{\"status\":\"OK\",\"message\":\"System state reset successfully\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static int fetchStock(String productId) {
        String res = querySocket("localhost", InventoryService.PORT, "GET_STOCK:" + productId);
        if (res.startsWith("OK:STOCK:")) {
            try {
                String[] parts = res.split(":");
                return Integer.parseInt(parts[3]);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static double fetchBalance(String customerId) {
        String res = querySocket("localhost", PaymentService.PORT, "GET_BALANCE:" + customerId);
        if (res.startsWith("OK:BALANCE:")) {
            try {
                String[] parts = res.split(":");
                return Double.parseDouble(parts[3]);
            } catch (Exception ignored) {}
        }
        return -1.0;
    }

    private static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String querySocket(String host, int port, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(4000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(message);
            String res = in.readLine();
            return res != null ? res : "FAIL:EMPTY";
        } catch (Exception e) {
            return "FAIL:SERVICE_UNAVAILABLE:" + e.getMessage();
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx + key.length());
        if (colon == -1) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end != -1) return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && (Character.isLetterOrDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
                end++;
            }
            return json.substring(start, end);
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String getDashboardHtml() {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <title>Distributed Order Processing System | DC Project</title>\n" +
            "  <style>\n" +
            "    :root {\n" +
            "      --bg: #0b1120;\n" +
            "      --card-bg: #1e293b;\n" +
            "      --card-border: #334155;\n" +
            "      --text: #f8fafc;\n" +
            "      --text-muted: #94a3b8;\n" +
            "      --primary: #3b82f6;\n" +
            "      --primary-hover: #2563eb;\n" +
            "      --success: #10b981;\n" +
            "      --warning: #f59e0b;\n" +
            "      --danger: #ef4444;\n" +
            "      --purple: #8b5cf6;\n" +
            "    }\n" +
            "    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif; }\n" +
            "    body { background-color: var(--bg); color: var(--text); padding: 24px; min-height: 100vh; }\n" +
            "    .container { max-width: 1300px; margin: 0 auto; }\n" +
            "    header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 20px; border-bottom: 1px solid var(--card-border); margin-bottom: 24px; flex-wrap: wrap; gap: 16px; }\n" +
            "    .title-group h1 { font-size: 1.6rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 10px; }\n" +
            "    .title-group p { font-size: 0.9rem; color: var(--text-muted); margin-top: 4px; }\n" +
            "    .badge { padding: 4px 10px; font-size: 0.75rem; border-radius: 9999px; font-weight: 600; text-transform: uppercase; }\n" +
            "    .badge-primary { background: rgba(59, 130, 246, 0.2); color: #60a5fa; border: 1px solid rgba(59, 130, 246, 0.3); }\n" +
            "    .badge-success { background: rgba(16, 185, 129, 0.2); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.3); }\n" +
            "    .badge-warning { background: rgba(245, 158, 11, 0.2); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.3); }\n" +
            "    .badge-danger { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); }\n" +
            "    .btn-group { display: flex; gap: 10px; align-items: center; }\n" +
            "    button { cursor: pointer; padding: 8px 16px; border-radius: 6px; font-weight: 600; border: none; transition: 0.2s all; display: inline-flex; align-items: center; gap: 6px; font-size: 0.85rem; }\n" +
            "    .btn-primary { background: var(--primary); color: #fff; }\n" +
            "    .btn-primary:hover { background: var(--primary-hover); }\n" +
            "    .btn-secondary { background: #334155; color: #e2e8f0; }\n" +
            "    .btn-secondary:hover { background: #475569; }\n" +
            "    .btn-danger { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); }\n" +
            "    .btn-danger:hover { background: rgba(239, 68, 68, 0.3); }\n" +
            "    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin-bottom: 24px; }\n" +
            "    .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; padding: 18px; }\n" +
            "    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid rgba(255, 255, 255, 0.05); }\n" +
            "    .card-title { font-size: 1rem; font-weight: 600; color: #f1f5f9; display: flex; align-items: center; gap: 8px; }\n" +
            "    .pulse { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }\n" +
            "    .pulse-online { background: var(--success); box-shadow: 0 0 8px var(--success); }\n" +
            "    .pulse-offline { background: var(--danger); box-shadow: 0 0 8px var(--danger); }\n" +
            "    .table-container { width: 100%; overflow-x: auto; }\n" +
            "    table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }\n" +
            "    th { text-align: left; padding: 8px; color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--card-border); }\n" +
            "    td { padding: 10px 8px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); }\n" +
            "    .stock-bar-bg { width: 100%; height: 6px; background: #334155; border-radius: 3px; overflow: hidden; margin-top: 4px; }\n" +
            "    .stock-bar-fill { height: 100%; background: var(--primary); transition: width 0.4s ease; }\n" +
            "    .main-layout { display: grid; grid-template-columns: 1fr 1.1fr; gap: 24px; margin-bottom: 24px; }\n" +
            "    @media(max-width: 900px) { .main-layout { grid-template-columns: 1fr; } }\n" +
            "    .form-group { margin-bottom: 14px; }\n" +
            "    label { display: block; font-size: 0.8rem; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; }\n" +
            "    select, input { width: 100%; padding: 10px; background: #0f172a; border: 1px solid var(--card-border); border-radius: 6px; color: #fff; font-size: 0.9rem; outline: none; transition: 0.2s border; }\n" +
            "    select:focus, input:focus { border-color: var(--primary); }\n" +
            "    .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }\n" +
            "    .scenarios-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin-top: 12px; }\n" +
            "    .scenario-btn { background: #0f172a; border: 1px solid var(--card-border); padding: 10px 12px; border-radius: 6px; text-align: left; color: #cbd5e1; font-size: 0.82rem; transition: 0.2s all; cursor: pointer; display: flex; flex-direction: column; gap: 4px; }\n" +
            "    .scenario-btn:hover { border-color: var(--primary); background: #1e293b; color: #fff; }\n" +
            "    .scenario-btn strong { font-size: 0.85rem; color: #60a5fa; }\n" +
            "    .saga-steps { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin-top: 16px; }\n" +
            "    .saga-step { background: #0f172a; border: 1px solid var(--card-border); border-radius: 8px; padding: 14px; text-align: center; position: relative; }\n" +
            "    .saga-step.active { border-color: var(--primary); box-shadow: 0 0 10px rgba(59, 130, 246, 0.2); }\n" +
            "    .saga-step.step-success { border-color: var(--success); }\n" +
            "    .saga-step.step-fail { border-color: var(--danger); }\n" +
            "    .saga-step.step-comp { border-color: var(--warning); background: rgba(245, 158, 11, 0.05); }\n" +
            "    .saga-step-num { width: 26px; height: 26px; border-radius: 50%; background: #334155; display: inline-flex; align-items: center; justify-content: center; font-size: 0.75rem; font-weight: bold; margin-bottom: 8px; }\n" +
            "    .step-success .saga-step-num { background: var(--success); color: #fff; }\n" +
            "    .step-fail .saga-step-num { background: var(--danger); color: #fff; }\n" +
            "    .step-comp .saga-step-num { background: var(--warning); color: #000; }\n" +
            "    .logs-box { background: #090e17; border: 1px solid var(--card-border); border-radius: 8px; padding: 14px; height: 320px; overflow-y: auto; font-family: 'Consolas', 'Courier New', monospace; font-size: 0.82rem; line-height: 1.5; }\n" +
            "    .log-line { display: flex; gap: 8px; margin-bottom: 4px; word-break: break-all; }\n" +
            "    .log-time { color: #64748b; min-width: 65px; }\n" +
            "    .log-source { font-weight: bold; min-width: 140px; }\n" +
            "    .src-OrderCoordinator { color: #60a5fa; }\n" +
            "    .src-InventoryService { color: #34d399; }\n" +
            "    .src-PaymentService { color: #fbbf24; }\n" +
            "    .src-ShippingService { color: #c084fc; }\n" +
            "    .src-OrderClient { color: #f472b6; }\n" +
            "    .lvl-SUCCESS { color: #34d399; }\n" +
            "    .lvl-FAIL { color: #f87171; font-weight: bold; }\n" +
            "    .lvl-COMPENSATION { color: #fbbf24; font-weight: bold; background: rgba(245, 158, 11, 0.1); padding: 0 4px; border-radius: 2px; }\n" +
            "    .order-result { margin-top: 14px; padding: 12px; border-radius: 6px; display: none; font-size: 0.88rem; }\n" +
            "    .order-result.success { background: rgba(16, 185, 129, 0.15); border: 1px solid var(--success); color: #34d399; display: block; }\n" +
            "    .order-result.error { background: rgba(239, 68, 68, 0.15); border: 1px solid var(--danger); color: #f87171; display: block; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class=\"container\">\n" +
            "  <header>\n" +
            "    <div class=\"title-group\">\n" +
            "      <h1>\n" +
            "        <svg width=\"24\" height=\"24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4\"/></svg>\n" +
            "        Distributed Order Processing System\n" +
            "      </h1>\n" +
            "      <p>Data Communication (DC) Mini Project &bull; Socket IPC &bull; Coordinator Pattern &bull; Saga Rollback</p>\n" +
            "    </div>\n" +
            "    <div class=\"btn-group\">\n" +
            "      <button class=\"btn-secondary\" onclick=\"fetchState(); fetchLogs();\">\n" +
            "        <svg width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15\"/></svg>\n" +
            "        Refresh\n" +
            "      </button>\n" +
            "      <button class=\"btn-danger\" onclick=\"resetDemo();\">\n" +
            "        <svg width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16\"/></svg>\n" +
            "        Reset Baseline State\n" +
            "      </button>\n" +
            "    </div>\n" +
            "  </header>\n" +
            "\n" +
            "  <!-- Microservices Status Cards -->\n" +
            "  <div class=\"grid\" id=\"servicesGrid\">\n" +
            "    <!-- Populated by JS -->\n" +
            "  </div>\n" +
            "\n" +
            "  <div class=\"main-layout\">\n" +
            "    <!-- Left Column: Interactive Order Dispatcher & Scenarios -->\n" +
            "    <div>\n" +
            "      <div class=\"card\" style=\"margin-bottom: 20px;\">\n" +
            "        <div class=\"card-header\">\n" +
            "          <span class=\"card-title\">\n" +
            "            <svg width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z\"/></svg>\n" +
            "            Dispatch Distributed Order (Socket Client)\n" +
            "          </span>\n" +
            "          <span class=\"badge badge-primary\">Port 5000</span>\n" +
            "        </div>\n" +
            "        <form id=\"orderForm\" onsubmit=\"submitOrder(event);\">\n" +
            "          <div class=\"form-row\">\n" +
            "            <div class=\"form-group\">\n" +
            "              <label>Select Customer</label>\n" +
            "              <select id=\"formCust\" onchange=\"updateCalc();\">\n" +
            "                <option value=\"CUST-101\">CUST-101 (Alice) - Balance: $5000</option>\n" +
            "                <option value=\"CUST-102\">CUST-102 (Bob) - Balance: $2500</option>\n" +
            "                <option value=\"CUST-103\">CUST-103 (Charlie) - Balance: $7500</option>\n" +
            "              </select>\n" +
            "            </div>\n" +
            "            <div class=\"form-group\">\n" +
            "              <label>Select Product</label>\n" +
            "              <select id=\"formProduct\" onchange=\"updateCalc();\">\n" +
            "                <option value=\"LAPTOP-1001\" data-price=\"1200\">LAPTOP-1001 ($1,200.00)</option>\n" +
            "                <option value=\"PHONE-2002\" data-price=\"500\">PHONE-2002 ($500.00)</option>\n" +
            "                <option value=\"HEADSET-3003\" data-price=\"150\">HEADSET-3003 ($150.00)</option>\n" +
            "                <option value=\"WATCH-4004\" data-price=\"250\">WATCH-4004 ($250.00)</option>\n" +
            "              </select>\n" +
            "            </div>\n" +
            "          </div>\n" +
            "          <div class=\"form-row\">\n" +
            "            <div class=\"form-group\">\n" +
            "              <label>Quantity</label>\n" +
            "              <input type=\"number\" id=\"formQty\" value=\"1\" min=\"1\" oninput=\"updateCalc();\">\n" +
            "            </div>\n" +
            "            <div class=\"form-group\">\n" +
            "              <label>Order Amount ($)</label>\n" +
            "              <input type=\"number\" id=\"formAmount\" value=\"1200.00\" step=\"0.01\">\n" +
            "            </div>\n" +
            "          </div>\n" +
            "          <div class=\"form-group\">\n" +
            "            <label>Delivery Address</label>\n" +
            "            <input type=\"text\" id=\"formAddress\" value=\"12 Main Street, Bengaluru\">\n" +
            "          </div>\n" +
            "          <button type=\"submit\" class=\"btn-primary\" style=\"width: 100%; justify-content: center; padding: 12px;\" id=\"submitBtn\">\n" +
            "            Send PLACE_ORDER Request\n" +
            "          </button>\n" +
            "        </form>\n" +
            "        <div id=\"orderResult\" class=\"order-result\"></div>\n" +
            "      </div>\n" +
            "\n" +
            "      <!-- Preset Test Scenarios (Viva Demo) -->\n" +
            "      <div class=\"card\">\n" +
            "        <div class=\"card-header\">\n" +
            "          <span class=\"card-title\">\n" +
            "            <svg width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M13 10V3L4 14h7v7l9-11h-7z\"/></svg>\n" +
            "            1-Click Automated Scenarios (Viva Demonstration)\n" +
            "          </span>\n" +
            "        </div>\n" +
            "        <div class=\"scenarios-grid\">\n" +
            "          <div class=\"scenario-btn\" onclick=\"runScenario(1);\">\n" +
            "            <strong>Test 1: Normal Order</strong>\n" +
            "            <span>CUST-101 buys Laptop ($1200) &bull; Full Success</span>\n" +
            "          </div>\n" +
            "          <div class=\"scenario-btn\" onclick=\"runScenario(2);\">\n" +
            "            <strong>Test 2: Insufficient Funds</strong>\n" +
            "            <span>CUST-102 charged $99,999 &bull; Stock Rolled Back</span>\n" +
            "          </div>\n" +
            "          <div class=\"scenario-btn\" onclick=\"runScenario(3);\">\n" +
            "            <strong>Test 3: Out of Stock</strong>\n" +
            "            <span>CUST-103 requests 999 watches &bull; Immediate Abort</span>\n" +
            "          </div>\n" +
            "          <div class=\"scenario-btn\" onclick=\"runScenario(4);\">\n" +
            "            <strong>Test 4: Bad Address</strong>\n" +
            "            <span>Invalid address 'N/A' &bull; Dual Refund & Stock Rollback</span>\n" +
            "          </div>\n" +
            "          <div class=\"scenario-btn\" onclick=\"runScenario(5);\">\n" +
            "            <strong>Test 5: Success Post-Rollback</strong>\n" +
            "            <span>CUST-102 buys Phone &bull; Consistent Ledger</span>\n" +
            "          </div>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "\n" +
            "    <!-- Right Column: Saga Visualizer, Live Inventory & Wallets -->\n" +
            "    <div>\n" +
            "      <!-- Saga Pipeline Visualizer -->\n" +
            "      <div class=\"card\" style=\"margin-bottom: 20px;\">\n" +
            "        <div class=\"card-header\">\n" +
            "          <span class=\"card-title\">\n" +
            "            <svg width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2\"/></svg>\n" +
            "            Distributed Saga Transaction Pipeline\n" +
            "          </span>\n" +
            "          <span id=\"sagaStatusBadge\" class=\"badge badge-primary\">Idle</span>\n" +
            "        </div>\n" +
            "        <div class=\"saga-steps\">\n" +
            "          <div class=\"saga-step\" id=\"step1Box\">\n" +
            "            <div class=\"saga-step-num\">1</div>\n" +
            "            <div style=\"font-weight: 600; font-size: 0.85rem;\">Inventory</div>\n" +
            "            <div style=\"font-size: 0.72rem; color: var(--text-muted); margin-top: 4px;\">Stock Reservation</div>\n" +
            "            <div id=\"step1Status\" style=\"font-size: 0.75rem; margin-top: 6px; font-weight: bold;\">Idle</div>\n" +
            "          </div>\n" +
            "          <div class=\"saga-step\" id=\"step2Box\">\n" +
            "            <div class=\"saga-step-num\">2</div>\n" +
            "            <div style=\"font-weight: 600; font-size: 0.85rem;\">Payment</div>\n" +
            "            <div style=\"font-size: 0.72rem; color: var(--text-muted); margin-top: 4px;\">Wallet Debit</div>\n" +
            "            <div id=\"step2Status\" style=\"font-size: 0.75rem; margin-top: 6px; font-weight: bold;\">Idle</div>\n" +
            "          </div>\n" +
            "          <div class=\"saga-step\" id=\"step3Box\">\n" +
            "            <div class=\"saga-step-num\">3</div>\n" +
            "            <div style=\"font-weight: 600; font-size: 0.85rem;\">Shipping</div>\n" +
            "            <div style=\"font-size: 0.72rem; color: var(--text-muted); margin-top: 4px;\">Tracking Consign</div>\n" +
            "            <div id=\"step3Status\" style=\"font-size: 0.75rem; margin-top: 6px; font-weight: bold;\">Idle</div>\n" +
            "          </div>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "\n" +
            "      <!-- Live Inventory & Wallets Tables -->\n" +
            "      <div class=\"grid\" style=\"grid-template-columns: 1fr 1fr; margin-bottom: 0;\">\n" +
            "        <div class=\"card\">\n" +
            "          <div class=\"card-header\">\n" +
            "            <span class=\"card-title\">Inventory Service (Port 6001)</span>\n" +
            "          </div>\n" +
            "          <div class=\"table-container\">\n" +
            "            <table>\n" +
            "              <thead><tr><th>Item</th><th>Stock</th></tr></thead>\n" +
            "              <tbody id=\"inventoryTableBody\"></tbody>\n" +
            "            </table>\n" +
            "          </div>\n" +
            "        </div>\n" +
            "        <div class=\"card\">\n" +
            "          <div class=\"card-header\">\n" +
            "            <span class=\"card-title\">Payment Service (Port 6002)</span>\n" +
            "          </div>\n" +
            "          <div class=\"table-container\">\n" +
            "            <table>\n" +
            "              <thead><tr><th>Customer</th><th>Balance</th></tr></thead>\n" +
            "              <tbody id=\"walletsTableBody\"></tbody>\n" +
            "            </table>\n" +
            "          </div>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "\n" +
            "  <!-- Live Transaction & Socket Log Stream -->\n" +
            "  <div class=\"card\">\n" +
            "    <div class=\"card-header\">\n" +
            "      <span class=\"card-title\">\n" +
            "        <svg width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path d=\"M4 6h16M4 12h16m-7 6h7\"/></svg>\n" +
            "        Distributed Event Stream & Socket Log Feed\n" +
            "      </span>\n" +
            "      <span style=\"font-size: 0.8rem; color: var(--text-muted);\" id=\"logCount\">0 events</span>\n" +
            "    </div>\n" +
            "    <div class=\"logs-box\" id=\"logsBox\"></div>\n" +
            "  </div>\n" +
            "</div>\n" +
            "\n" +
            "<script>\n" +
            "  function updateCalc() {\n" +
            "    const prodSelect = document.getElementById('formProduct');\n" +
            "    const price = parseFloat(prodSelect.selectedOptions[0].getAttribute('data-price') || 0);\n" +
            "    const qty = parseInt(document.getElementById('formQty').value || 1);\n" +
            "    document.getElementById('formAmount').value = (price * qty).toFixed(2);\n" +
            "  }\n" +
            "\n" +
            "  function setSagaState(s1, s2, s3, badgeText, badgeClass) {\n" +
            "    const b1 = document.getElementById('step1Box');\n" +
            "    const b2 = document.getElementById('step2Box');\n" +
            "    const b3 = document.getElementById('step3Box');\n" +
            "    b1.className = 'saga-step ' + s1.cls;\n" +
            "    document.getElementById('step1Status').innerText = s1.text;\n" +
            "    b2.className = 'saga-step ' + s2.cls;\n" +
            "    document.getElementById('step2Status').innerText = s2.text;\n" +
            "    b3.className = 'saga-step ' + s3.cls;\n" +
            "    document.getElementById('step3Status').innerText = s3.text;\n" +
            "    const badge = document.getElementById('sagaStatusBadge');\n" +
            "    badge.innerText = badgeText;\n" +
            "    badge.className = 'badge ' + badgeClass;\n" +
            "  }\n" +
            "\n" +
            "  async function fetchState() {\n" +
            "    try {\n" +
            "      const res = await fetch('/api/state');\n" +
            "      const data = await res.json();\n" +
            "      // Render microservices\n" +
            "      const servicesGrid = document.getElementById('servicesGrid');\n" +
            "      servicesGrid.innerHTML = data.services.map(s => `\n" +
            "        <div class=\"card\" style=\"padding: 12px 16px;\">\n" +
            "          <div style=\"display: flex; justify-content: space-between; align-items: center;\">\n" +
            "            <div>\n" +
            "              <div style=\"font-weight: 600; font-size: 0.9rem;\">${s.name}</div>\n" +
            "              <div style=\"font-size: 0.75rem; color: var(--text-muted);\">Port ${s.port} &bull; TCP Socket</div>\n" +
            "            </div>\n" +
            "            <span class=\"badge ${s.status === 'ONLINE' ? 'badge-success' : 'badge-danger'}\">\n" +
            "              <span class=\"pulse ${s.status === 'ONLINE' ? 'pulse-online' : 'pulse-offline'}\"></span> ${s.status}\n" +
            "            </span>\n" +
            "          </div>\n" +
            "        </div>\n" +
            "      `).join('');\n" +
            "      // Render inventory\n" +
            "      const invTbody = document.getElementById('inventoryTableBody');\n" +
            "      invTbody.innerHTML = data.inventory.map(item => {\n" +
            "        const pct = Math.min(100, Math.round((item.stock / item.initial) * 100));\n" +
            "        const color = item.stock <= 5 ? 'var(--danger)' : (item.stock < item.initial ? 'var(--warning)' : 'var(--primary)');\n" +
            "        return `<tr>\n" +
            "          <td><strong>${item.name}</strong><br><span style=\"color:var(--text-muted);font-size:0.75rem;\">${item.id}</span></td>\n" +
            "          <td>\n" +
            "            <span style=\"font-weight: bold;\">${item.stock}</span> / ${item.initial}\n" +
            "            <div class=\"stock-bar-bg\"><div class=\"stock-bar-fill\" style=\"width:${pct}%;background:${color};\"></div></div>\n" +
            "          </td>\n" +
            "        </tr>`;\n" +
            "      }).join('');\n" +
            "      // Render wallets\n" +
            "      const walTbody = document.getElementById('walletsTableBody');\n" +
            "      walTbody.innerHTML = data.wallets.map(w => {\n" +
            "        const pct = Math.min(100, Math.round((w.balance / w.initial) * 100));\n" +
            "        return `<tr>\n" +
            "          <td><strong>${w.name}</strong></td>\n" +
            "          <td>\n" +
            "            <span style=\"font-weight: bold; color: #34d399;\">$${w.balance.toFixed(2)}</span>\n" +
            "            <div class=\"stock-bar-bg\"><div class=\"stock-bar-fill\" style=\"width:${pct}%;background:#10b981;\"></div></div>\n" +
            "          </td>\n" +
            "        </tr>`;\n" +
            "      }).join('');\n" +
            "    } catch(err) { console.error('Fetch state error:', err); }\n" +
            "  }\n" +
            "\n" +
            "  async function fetchLogs() {\n" +
            "    try {\n" +
            "      const res = await fetch('/api/logs');\n" +
            "      const logs = await res.json();\n" +
            "      const box = document.getElementById('logsBox');\n" +
            "      document.getElementById('logCount').innerText = logs.length + ' events';\n" +
            "      box.innerHTML = logs.map(l => `\n" +
            "        <div class=\"log-line\">\n" +
            "          <span class=\"log-time\">[${l.time}]</span>\n" +
            "          <span class=\"log-source src-${l.source}\">[${l.source}]</span>\n" +
            "          <span class=\"lvl-${l.level}\">${escapeHtml(l.message)}</span>\n" +
            "        </div>\n" +
            "      `).join('');\n" +
            "      box.scrollTop = box.scrollHeight;\n" +
            "    } catch(e) {}\n" +
            "  }\n" +
            "\n" +
            "  function escapeHtml(str) {\n" +
            "    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');\n" +
            "  }\n" +
            "\n" +
            "  async function submitOrder(e) {\n" +
            "    if (e) e.preventDefault();\n" +
            "    const customerId = document.getElementById('formCust').value;\n" +
            "    const productId = document.getElementById('formProduct').value;\n" +
            "    const quantity = document.getElementById('formQty').value;\n" +
            "    const amount = document.getElementById('formAmount').value;\n" +
            "    const address = document.getElementById('formAddress').value;\n" +
            "    const submitBtn = document.getElementById('submitBtn');\n" +
            "    submitBtn.disabled = true;\n" +
            "    submitBtn.innerText = 'Processing via Distributed Coordinator...';\n" +
            "    setSagaState({cls:'active', text:'Reserving...'}, {cls:'', text:'Waiting'}, {cls:'', text:'Waiting'}, 'In Progress', 'badge-primary');\n" +
            "    const resultBox = document.getElementById('orderResult');\n" +
            "    try {\n" +
            "      const res = await fetch('/api/order', {\n" +
            "        method: 'POST',\n" +
            "        headers: { 'Content-Type': 'application/json' },\n" +
            "        body: JSON.stringify({ customerId, productId, quantity, amount, address })\n" +
            "      });\n" +
            "      const data = await res.json();\n" +
            "      if (data.success) {\n" +
            "        setSagaState({cls:'step-success', text:'Reserved'}, {cls:'step-success', text:'Debited'}, {cls:'step-success', text:'Dispatched'}, 'CONFIRMED', 'badge-success');\n" +
            "        resultBox.className = 'order-result success';\n" +
            "        resultBox.innerHTML = `<strong>Order Confirmed!</strong><br>Order ID: ${data.orderId}<br>Tracking ID: ${data.trackingId}`;\n" +
            "      } else {\n" +
            "        resultBox.className = 'order-result error';\n" +
            "        resultBox.innerHTML = `<strong>Order Failed:</strong> ${escapeHtml(data.raw)}`;\n" +
            "        if (data.raw.includes('INVENTORY:')) {\n" +
            "          setSagaState({cls:'step-fail', text:'Stock Exceeded'}, {cls:'', text:'Skipped'}, {cls:'', text:'Skipped'}, 'Inventory Failed', 'badge-danger');\n" +
            "        } else if (data.raw.includes('PAYMENT:')) {\n" +
            "          setSagaState({cls:'step-comp', text:'Rolled Back'}, {cls:'step-fail', text:'Payment Rejected'}, {cls:'', text:'Skipped'}, 'Saga Rollback', 'badge-warning');\n" +
            "        } else if (data.raw.includes('SHIPPING:')) {\n" +
            "          setSagaState({cls:'step-comp', text:'Rolled Back'}, {cls:'step-comp', text:'Refunded'}, {cls:'step-fail', text:'Invalid Address'}, 'Full Rollback', 'badge-warning');\n" +
            "        }\n" +
            "      }\n" +
            "    } catch(err) {\n" +
            "      resultBox.className = 'order-result error';\n" +
            "      resultBox.innerText = 'Network error communicating with Web Server: ' + err.message;\n" +
            "    } finally {\n" +
            "      submitBtn.disabled = false;\n" +
            "      submitBtn.innerText = 'Send PLACE_ORDER Request';\n" +
            "      fetchState();\n" +
            "      fetchLogs();\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  function runScenario(num) {\n" +
            "    if (num === 1) {\n" +
            "      document.getElementById('formCust').value = 'CUST-101';\n" +
            "      document.getElementById('formProduct').value = 'LAPTOP-1001';\n" +
            "      document.getElementById('formQty').value = '1';\n" +
            "      document.getElementById('formAmount').value = '1200.00';\n" +
            "      document.getElementById('formAddress').value = '12 Main Street, Bengaluru';\n" +
            "    } else if (num === 2) {\n" +
            "      document.getElementById('formCust').value = 'CUST-102';\n" +
            "      document.getElementById('formProduct').value = 'LAPTOP-1001';\n" +
            "      document.getElementById('formQty').value = '1';\n" +
            "      document.getElementById('formAmount').value = '99999.00';\n" +
            "      document.getElementById('formAddress').value = '45 Park Road, Chennai';\n" +
            "    } else if (num === 3) {\n" +
            "      document.getElementById('formCust').value = 'CUST-103';\n" +
            "      document.getElementById('formProduct').value = 'WATCH-4004';\n" +
            "      document.getElementById('formQty').value = '999';\n" +
            "      document.getElementById('formAmount').value = '250.00';\n" +
            "      document.getElementById('formAddress').value = '21 Lake View, Pune';\n" +
            "    } else if (num === 4) {\n" +
            "      document.getElementById('formCust').value = 'CUST-101';\n" +
            "      document.getElementById('formProduct').value = 'PHONE-2002';\n" +
            "      document.getElementById('formQty').value = '1';\n" +
            "      document.getElementById('formAmount').value = '500.00';\n" +
            "      document.getElementById('formAddress').value = 'N/A';\n" +
            "    } else if (num === 5) {\n" +
            "      document.getElementById('formCust').value = 'CUST-102';\n" +
            "      document.getElementById('formProduct').value = 'PHONE-2002';\n" +
            "      document.getElementById('formQty').value = '2';\n" +
            "      document.getElementById('formAmount').value = '700.00';\n" +
            "      document.getElementById('formAddress').value = '77 Tech Park, Chennai';\n" +
            "    }\n" +
            "    submitOrder();\n" +
            "  }\n" +
            "\n" +
            "  async function resetDemo() {\n" +
            "    if (!confirm('Reset inventory and wallet balances to baseline defaults?')) return;\n" +
            "    await fetch('/api/reset', { method: 'POST' });\n" +
            "    setSagaState({cls:'', text:'Idle'}, {cls:'', text:'Idle'}, {cls:'', text:'Idle'}, 'Idle', 'badge-primary');\n" +
            "    document.getElementById('orderResult').style.display = 'none';\n" +
            "    fetchState();\n" +
            "    fetchLogs();\n" +
            "  }\n" +
            "\n" +
            "  fetchState();\n" +
            "  fetchLogs();\n" +
            "  setInterval(fetchLogs, 2500);\n" +
            "  setInterval(fetchState, 4000);\n" +
            "</script>\n" +
            "</body>\n" +
            "</html>";
    }
}
