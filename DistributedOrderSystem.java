package com.ecommerce;

public class DistributedOrderSystem {
    public static void main(String[] args) {
        System.out.println("Starting Distributed E-Commerce Order Processing Services...");

        startService(new InventoryService(), "InventoryService");
        startService(new PaymentService(), "PaymentService");
        startService(new ShippingService(), "ShippingService");
        startService(new OrderCoordinator(), "OrderCoordinator");
        startService(new WebDashboardServer(), "WebDashboardServer");

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        System.out.println("\nAll distributed services are active and listening on their ports.");
        System.out.println("- OrderCoordinator : Port 5000");
        System.out.println("- InventoryService : Port 6001");
        System.out.println("- PaymentService   : Port 6002");
        System.out.println("- ShippingService  : Port 6003");
        System.out.println("- Web Dashboard    : http://localhost:8080 (Interactive UI)");
        System.out.println("Press Ctrl+C to terminate the system.\n");

        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void startService(Runnable service, String name) {
        Thread thread = new Thread(service, name);
        thread.setDaemon(false);
        thread.start();
    }
}
