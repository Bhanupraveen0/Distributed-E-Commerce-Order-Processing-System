# Distributed E-Commerce Order Processing System

A distributed order processing system implemented using pure Java socket programming without external dependencies or frameworks, designed for Distributed Computing (DC).

## Architecture

The system implements the **Distributed Coordinator Pattern** with **Saga-based Compensating Transactions** for failure recovery:

- **OrderCoordinator** (`Port 5000`):
  - Receives customer order requests from clients.
  - Sequentially orchestrates multi-step distributed operations:
    1. Stock reservation with `InventoryService`
    2. Payment authorization and debit with `PaymentService`
    3. Shipping dispatch and tracking generation with `ShippingService`
  - **Saga Rollback / Compensation**:
    - If payment fails, it automatically contacts `InventoryService` to restore reserved stock.
    - If shipping fails, it automatically refunds the customer's wallet on `PaymentService` and restores reserved stock on `InventoryService`.
- **InventoryService** (`Port 6001`):
  - Manages stock levels (`LAPTOP-1001`, `PHONE-2002`, `HEADSET-3003`, `WATCH-4004`).
  - Supports `CHECK_STOCK`, `RELEASE_STOCK` (rollback compensation), and `GET_STOCK`.
- **PaymentService** (`Port 6002`):
  - Manages customer digital wallets (`CUST-101`, `CUST-102`, `CUST-103`).
  - Supports `PAY`, `REFUND` (rollback compensation), and `GET_BALANCE`.
- **ShippingService** (`Port 6003`):
  - Validates delivery addresses and assigns unique tracking identifiers (`TRK-1001`, `TRK-1002`, ...).
- **WebDashboardServer** (`Port 8080`):
  - Provides a modern interactive web dashboard and Saga transaction visualizer in the browser.
  - Live stock and customer wallet monitors, 1-click test scenario triggers, and real-time socket event stream.

---

## Quick Run (Windows)

### Option 0: Interactive Web Dashboard (Browser UI)
Double-click `start_website.bat` or run in PowerShell:
```powershell
.\run_website.ps1
```
Then open your browser to **http://localhost:8080**.

### Option 1: One-Click Demo (Automated Scenarios in Terminal)
Double-click `run_demo.bat` or run in PowerShell:
```powershell
.\run_demo.ps1
```

### Option 2: Separate Terminals

1. **Terminal 1 (Start Server & Distributed Services)**:
   ```cmd
   start_server.bat
   ```
   *Or manually:*
   ```cmd
   javac -d out src/com/ecommerce/*.java
   java -cp out com.ecommerce.DistributedOrderSystem
   ```

2. **Terminal 2 (Run Client Tests)**:
   ```cmd
   start_client.bat
   ```
   *Or interactive mode:*
   ```cmd
   java -cp out com.ecommerce.OrderClient --interactive
   ```

---

## Test Scenarios Demonstrated

The test suite in `OrderClient` executes 5 distributed scenarios:

1. **Standard Successful Order**: CUST-101 orders Laptop -> Stock reserved, balance debited, tracking ID `TRK-1001` generated.
2. **Payment Failure & Saga Rollback**: CUST-102 orders Laptop with insufficient funds ($99,999) -> Payment rejected, coordinator rolls back reserved stock in `InventoryService`.
3. **Inventory Failure**: CUST-103 requests 999 watches (exceeding stock) -> Coordinator immediately aborts before debiting wallet.
4. **Shipping Failure & Full Rollback**: Invalid address ("N/A") -> Shipping rejected, coordinator refunds $500 to customer and releases reserved phone back to inventory.
5. **Success Post-Rollback**: CUST-102 orders phone with restored wallet and inventory -> Confirms transactional consistency.

---

## Example Output

### Client:
```text
>>> Scenario 1: Test 1 [SUCCESS]: Standard order for CUST-101 (Laptop)
Request  : PLACE_ORDER:CUST-101:LAPTOP-1001:1:1200.00:12 Main Street, Bengaluru
Response : ORDER_CONFIRMED:ORD-1792002618687:TRK-1001:LAPTOP-1001
------------------------------------------------------------------

>>> Scenario 2: Test 2 [PAYMENT FAILURE & ROLLBACK]: CUST-102 insufficient funds
Request  : PLACE_ORDER:CUST-102:LAPTOP-1001:1:99999.00:45 Park Road, Chennai
Response : ORDER_FAILED:PAYMENT:FAIL:INSUFFICIENT_FUNDS:CUST-102:2500.0
------------------------------------------------------------------
```

### Server (Coordinator & Services):
```text
[OrderCoordinator] Initiating Order ORD-... [Customer: CUST-102, Product: LAPTOP-1001, Quantity: 1, Amount: $99999.0]
[OrderCoordinator] Step 1: Requesting stock reservation from InventoryService...
[InventoryService] Reserved 1 of LAPTOP-1001 (Remaining: 13)
[OrderCoordinator] Step 1 Succeeded: Stock reserved.
[OrderCoordinator] Step 2: Requesting payment processing from PaymentService...
[PaymentService] Payment failed for CUST-102: Insufficient funds (Required: $99999.0, Balance: $2500.0)
[OrderCoordinator] Step 2 Failed: FAIL:INSUFFICIENT_FUNDS:CUST-102:2500.0
[OrderCoordinator] [SAGA ROLLBACK] Triggering compensation: Releasing reserved stock...
[InventoryService] [COMPENSATION] Restored 1 of LAPTOP-1001 (New Stock: 14)
[OrderCoordinator] Inventory rollback result: OK:STOCK_RELEASED:LAPTOP-1001:14
```
