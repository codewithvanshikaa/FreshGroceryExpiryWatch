import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class ConsoleInput {

    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public static class EndOfInputException extends RuntimeException {
        public EndOfInputException() {
            super("end of input");
        }
    }

    private final BufferedReader reader;
    private final PrintStream out;
    private final boolean echo;

    public ConsoleInput(BufferedReader reader, PrintStream out, boolean echo) {
        this.reader = reader;
        this.out = out;
        this.echo = echo;
    }

    public String readRaw(String prompt) {
        out.print(prompt + ": ");
        out.flush();
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new EndOfInputException();
        }
        if (line == null) {
            throw new EndOfInputException();
        }
        line = line.trim();
        if (echo) {
            out.println(line);
        }
        if (line.equalsIgnoreCase("cancel")) {
            throw new CancelledException();
        }
        return line;
    }

    public String readText(String label) {
        while (true) {
            String value = readRaw(label);
            try {
                return InputValidator.requireText(value, label);
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public String readOptionalText(String prompt) {
        while (true) {
            String value = readRaw(prompt);
            try {
                return InputValidator.optionalText(value, "Remarks");
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public String readId(String label) {
        while (true) {
            String value = readRaw(label);
            try {
                return InputValidator.requireId(value, label);
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public int readPositiveInt(String label) {
        while (true) {
            String value = readRaw(label);
            try {
                return InputValidator.requirePositive(Integer.parseInt(value), label);
            } catch (NumberFormatException e) {
                out.println("  ! " + label + " must be a whole number.");
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public int readIntInRange(String label, int min, int max) {
        while (true) {
            String value = readRaw(label + " (" + min + "-" + max + ")");
            try {
                int number = Integer.parseInt(value);
                if (number >= min && number <= max) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // not a number, the error is printed below
            }
            out.println("  ! Please enter a whole number between " + min + " and " + max + ".");
        }
    }

    public BigDecimal readMoney(String label) {
        while (true) {
            String value = readRaw(label);
            try {
                return InputValidator.requireNonNegative(MoneyUtil.parse(value, label), label);
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public LocalDate readDate(String label) {
        while (true) {
            String value = readRaw(label + " (yyyy-MM-dd)");
            try {
                return DateUtil.parse(value, label);
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public LocalDate readDateOrDefault(String label, LocalDate defaultDate) {
        while (true) {
            String value = readRaw(label + " (yyyy-MM-dd, Enter = " + defaultDate + ")");
            if (value.isEmpty()) {
                return defaultDate;
            }
            try {
                return DateUtil.parse(value, label);
            } catch (InvalidInputException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    public boolean confirm(String question) {
        while (true) {
            String value = readRaw(question + " (y/n)").toLowerCase();
            if (value.equals("y") || value.equals("yes")) {
                return true;
            }
            if (value.equals("n") || value.equals("no")) {
                return false;
            }
            out.println("  ! Please answer y or n.");
        }
    }

    public Category readCategory() {
        Category[] all = Category.values();
        out.println("  Categories:");
        for (int i = 0; i < all.length; i++) {
            out.println("    " + (i + 1) + ". " + all[i].getDisplayName());
        }
        return all[readIntInRange("Category number", 1, all.length) - 1];
    }
}

// menu only handles input/output, the rules are in the service classes
class ConsoleMenu {

    private static final String[] MENU_ITEMS = {
            "Add New Grocery Product",
            "Add New Product Batch",
            "View All Products",
            "View Batch-Wise Inventory",
            "Record Sale",
            "View Expired Stock",
            "View Near-Expiry Stock",
            "Configure Near-Expiry Alert Days",
            "Remove Damaged Stock",
            "Remove Expired Stock",
            "Generate Inventory Report",
            "Generate Sales Report",
            "Generate Wastage Report",
            "View Transaction History",
            "Exit"
    };

    private final ConsoleInput in;
    private final PrintStream out;
    private final AppServices app;
    private final InventoryManager inventory;
    private final ExpiryService expiry;

    public ConsoleMenu(ConsoleInput in, PrintStream out, AppServices app) {
        this.in = in;
        this.out = out;
        this.app = app;
        this.inventory = app.inventory;
        this.expiry = app.expiry;
    }

    public void run() {
        try {
            if (!startUp()) {
                return;
            }
            boolean running = true;
            while (running) {
                printMenu();
                int choice = readChoice();
                running = handle(choice);
            }
        } catch (ConsoleInput.EndOfInputException e) {
            out.println("\nInput closed. Exiting (all completed changes are already saved).");
        }
    }

    private int readChoice() {
        try {
            String raw = in.readRaw("Enter your choice (1-" + MENU_ITEMS.length + ")");
            return Integer.parseInt(raw);
        } catch (NumberFormatException | ConsoleInput.CancelledException e) {
            return -1;
        }
    }

    private boolean handle(int choice) {
        try {
            switch (choice) {
                case 1 -> addProduct();
                case 2 -> addBatch();
                case 3 -> viewAllProducts();
                case 4 -> viewBatchInventory();
                case 5 -> recordSale();
                case 6 -> showReport(ReportType.EXPIRED);
                case 7 -> showReport(ReportType.NEAR_EXPIRY);
                case 8 -> configureThreshold();
                case 9 -> removeDamaged();
                case 10 -> removeExpired();
                case 11 -> showReport(ReportType.INVENTORY);
                case 12 -> showReport(ReportType.SALES);
                case 13 -> showReport(ReportType.WASTAGE);
                case 14 -> transactionHistory();
                case 15 -> {
                    save();
                    out.println("\nData saved in " + app.store.getDataDir() + ". Goodbye!");
                    return false;
                }
                default -> out.println("\n  Invalid choice. Please enter a number from 1 to " + MENU_ITEMS.length + ".");
            }
        } catch (InventoryException e) {
            out.println("\n  ERROR: " + e.getMessage());
        } catch (ConsoleInput.CancelledException e) {
            out.println("\n  Cancelled. Back to the main menu.");
        }
        return true;
    }

    private boolean startUp() {
        out.print(TablePrinter.banner("FRESH GROCERY EXPIRY WATCH"));
        if (app.store.hasSavedData()) {
            try {
                List<String> warnings = app.store.load();
                out.println("Saved data loaded from " + app.store.getDataDir() + ".");
                warnings.forEach(w -> out.println("  WARNING: " + w));
            } catch (PersistenceException e) {
                out.println("\nCould not load the saved data:");
                out.println("  " + e.getMessage());
                out.println("\nThe files were NOT changed. Fix or delete the file named above in "
                        + app.store.getDataDir() + " and start the application again.");
                return false;
            }
        } else {
            out.println("No saved data found (first run).");
            try {
                if (in.confirm("Load sample grocery data so you can try the application?")) {
                    app.sampleLoader.load();
                    save();
                    out.println("  Sample data loaded: " + inventory.getAllProducts().size() + " products, "
                            + inventory.getAllBatches().size() + " batches.");
                }
            } catch (InventoryException e) {
                out.println("  Could not load sample data: " + e.getMessage());
            } catch (ConsoleInput.CancelledException e) {
                out.println("  Starting with an empty inventory.");
            }
        }
        return true;
    }

    private void save() {
        try {
            app.store.save();
        } catch (PersistenceException e) {
            out.println("  WARNING: the change was made in memory but could NOT be saved to disk: " + e.getMessage());
        }
    }

    private void printMenu() {
        Map<StockStatus, Long> counts = expiry.countByStatus();
        out.println();
        out.print(TablePrinter.banner("MAIN MENU"));
        out.println(" Today: " + inventory.today() + "   Near-expiry alert: " + app.config.getNearExpiryDays() + " day(s)");
        out.println(" Stock alert: " + counts.get(StockStatus.EXPIRED) + " expired, "
                + counts.get(StockStatus.NEAR_EXPIRY) + " near-expiry batch(es)");
        out.println("--------------------------------------------------");
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            out.printf("%3d. %s%n", i + 1, MENU_ITEMS[i]);
        }
        out.println("--------------------------------------------------");
    }

    private void title(String text) {
        out.println("\n=== " + text + " ===");
        out.println("(type 'cancel' at any prompt to go back)");
    }

    private void addProduct() throws InventoryException {
        title("ADD NEW GROCERY PRODUCT");
        String id = in.readId("Product ID");
        if (inventory.productExists(id)) {
            throw new DuplicateEntryException("Product ID " + id.toUpperCase() + " already exists.");
        }
        String name = in.readText("Product name");
        Category category = in.readCategory();
        Product product = new Product(id, name, category);
        inventory.addProduct(product);
        save();
        out.println("  SUCCESS: Product added -> " + product);
    }

    private void addBatch() throws InventoryException {
        title("ADD NEW PRODUCT BATCH");
        if (inventory.getAllProducts().isEmpty()) {
            out.println("  No products yet. Add a product first (option 1).");
            return;
        }
        out.print(new InventoryReport(inventory, expiry).renderProductTotals());
        String productId = in.readId("Product ID");
        Product product = inventory.getProduct(productId);
        String batchNumber = in.readId("Batch number");
        if (inventory.batchExists(batchNumber)) {
            throw new DuplicateEntryException("Batch number " + batchNumber.toUpperCase() + " already exists.");
        }
        int quantity = in.readPositiveInt("Received quantity");
        BigDecimal cost = in.readMoney("Unit purchase cost (Rs.)");
        BigDecimal price = in.readMoney("Unit selling price (Rs.)");
        LocalDate received = in.readDateOrDefault("Received date", inventory.today());
        LocalDate expiryDate = in.readDate("Expiry date");

        Batch batch = new Batch(batchNumber, product.getProductId(), quantity, cost, price, received, expiryDate);
        boolean confirmedPastExpiry = false;
        if (inventory.isAlreadyExpired(batch)) {
            out.println("  WARNING: this batch is already expired (expiry date " + expiryDate + ").");
            if (!in.confirm("Add it anyway?")) {
                out.println("  Batch not added.");
                return;
            }
            confirmedPastExpiry = true;
        }
        inventory.addBatch(batch, confirmedPastExpiry);
        save();
        out.println("  SUCCESS: Batch " + batch.getBatchNumber() + " added for " + product.getName() + ": "
                + quantity + " unit(s), expires " + expiryDate + " " + expiry.statusLabel(batch));
    }

    private void viewAllProducts() {
        title("ALL PRODUCTS");
        out.print(new InventoryReport(inventory, expiry).renderProductTotals());
    }

    private void viewBatchInventory() {
        title("BATCH-WISE INVENTORY");
        out.print(new InventoryReport(inventory, expiry).renderBatchTable());
    }

    private void recordSale() throws InventoryException {
        title("RECORD SALE (FEFO: earliest expiry is sold first)");
        if (inventory.getAllProducts().isEmpty()) {
            out.println("  No products yet. Add a product first (option 1).");
            return;
        }
        out.print(new InventoryReport(inventory, expiry).renderProductTotals());
        String productId = in.readId("Product ID");
        Product product = inventory.getProduct(productId);
        out.println("  Selected: " + product.getName() + " - " + inventory.getTotalAvailable(productId)
                + " unit(s) on hand (expired units cannot be sold)");
        int quantity = in.readPositiveInt("Quantity to sell");

        List<SaleLine> plan = app.sales.planSale(productId, quantity);
        out.println("\n  Stock will be taken in this order:");
        TablePrinter table = new TablePrinter("Batch", "Expiry", "Status", "Qty", "Unit price (Rs.)", "Amount (Rs.)")
                .rightAlign(3, 4, 5);
        for (SaleLine line : plan) {
            Batch batch = inventory.findBatch(line.getBatchNumber());
            table.addRow(line.getBatchNumber(), batch.getExpiryDate().toString(), expiry.statusLabel(batch),
                    String.valueOf(line.getQuantity()), MoneyUtil.format(line.getUnitPrice()),
                    MoneyUtil.format(line.getLineTotal()));
        }
        out.print(table.render());
        BigDecimal total = MoneyUtil.sum(plan.stream().map(SaleLine::getLineTotal));
        out.println("  Total: Rs. " + MoneyUtil.format(total));
        for (String batchNumber : app.sales.findBelowCostBatchNumbers(plan)) {
            out.println("  WARNING: batch " + batchNumber + " is being sold below its purchase cost.");
        }
        if (!in.confirm("Confirm sale?")) {
            out.println("  Sale cancelled. No stock was changed.");
            return;
        }
        SaleTransaction sale = app.sales.recordSale(productId, quantity);
        save();
        out.println("  SUCCESS: Sale " + sale.getTransactionId() + " recorded - " + sale.getTotalQuantity()
                + " unit(s), Rs. " + MoneyUtil.format(sale.getTotalAmount()));
    }

    private void configureThreshold() throws InventoryException {
        title("CONFIGURE NEAR-EXPIRY ALERT DAYS");
        out.println("  Current setting: " + app.config.getNearExpiryDays() + " day(s)");
        int days = in.readIntInRange("New number of days", 1, 365);
        app.config.setNearExpiryDays(days);
        save();
        out.println("  SUCCESS: Near-expiry alert set to " + days + " day(s). "
                + expiry.getNearExpiryBatches().size() + " batch(es) are near expiry now.");
    }

    private void printStockTable(List<Batch> batches) {
        TablePrinter table = new TablePrinter("Batch", "Product", "Name", "Available", "Unit cost (Rs.)",
                "Expiry", "Status").rightAlign(3, 4);
        for (Batch b : batches) {
            table.addRow(b.getBatchNumber(), b.getProductId(), inventory.getProductName(b.getProductId()),
                    String.valueOf(b.getAvailableQuantity()), MoneyUtil.format(b.getUnitPurchaseCost()),
                    b.getExpiryDate().toString(), expiry.statusLabel(b));
        }
        out.print(table.render());
    }

    private Batch requireBatchWithStock(String batchNumber) throws InventoryException {
        Batch batch = inventory.findBatch(batchNumber);
        if (batch.isOutOfStock()) {
            throw new InvalidRemovalException("Batch " + batch.getBatchNumber() + " has no stock left to remove.");
        }
        return batch;
    }

    private void removeDamaged() throws InventoryException {
        title("REMOVE DAMAGED STOCK");
        List<Batch> inStock = inventory.getAllBatches().stream().filter(b -> !b.isOutOfStock()).toList();
        if (inStock.isEmpty()) {
            out.println("  There is no stock to remove.");
            return;
        }
        printStockTable(inStock);
        Batch batch = requireBatchWithStock(in.readId("Batch number"));
        out.println("  Batch " + batch.getBatchNumber() + " has " + batch.getAvailableQuantity() + " unit(s) available.");
        int quantity = in.readIntInRange("Quantity damaged", 1, batch.getAvailableQuantity());
        String remarks = in.readOptionalText("Remarks (optional, Enter to skip)");
        out.println("  This removes " + quantity + " unit(s) and records a wastage cost of Rs. "
                + MoneyUtil.format(MoneyUtil.multiply(batch.getUnitPurchaseCost(), quantity)) + ".");
        if (!in.confirm("Confirm removal?")) {
            out.println("  Removal cancelled. No stock was changed.");
            return;
        }
        StockRemoval removal = app.removal.removeDamaged(batch.getBatchNumber(), quantity, remarks);
        save();
        out.println("  SUCCESS: " + removal.summaryLine());
    }

    private void removeExpired() throws InventoryException {
        title("REMOVE EXPIRED STOCK");
        List<Batch> expired = expiry.getExpiredBatches();
        if (expired.isEmpty()) {
            out.println("  There is no expired stock to remove.");
            return;
        }
        printStockTable(expired);
        out.println("\n  1. Remove expired stock from one batch");
        out.println("  2. Remove ALL expired stock listed above");
        out.println("  0. Back");
        int option = in.readIntInRange("Choose an option", 0, 2);
        if (option == 0) {
            return;
        }
        if (option == 1) {
            Batch batch = requireBatchWithStock(in.readId("Batch number"));
            if (expiry.classify(batch) != StockStatus.EXPIRED) {
                throw new InvalidRemovalException("Batch " + batch.getBatchNumber() + " is not expired (expires on "
                        + batch.getExpiryDate() + "). Use 'Remove Damaged Stock' if it is damaged.");
            }
            int quantity = in.readIntInRange("Quantity to remove", 1, batch.getAvailableQuantity());
            String remarks = in.readOptionalText("Remarks (optional, Enter to skip)");
            out.println("  This removes " + quantity + " unit(s) and records a wastage cost of Rs. "
                    + MoneyUtil.format(MoneyUtil.multiply(batch.getUnitPurchaseCost(), quantity)) + ".");
            if (!in.confirm("Confirm removal?")) {
                out.println("  Removal cancelled. No stock was changed.");
                return;
            }
            StockRemoval removal = app.removal.removeExpired(batch.getBatchNumber(), quantity, remarks);
            save();
            out.println("  SUCCESS: " + removal.summaryLine());
        } else {
            int units = expired.stream().mapToInt(Batch::getAvailableQuantity).sum();
            String remarks = in.readOptionalText("Remarks (optional, Enter to skip)");
            out.println("  This removes " + units + " unit(s) from " + expired.size()
                    + " expired batch(es), wastage cost Rs. " + MoneyUtil.format(expiry.getExpiredStockCost()) + ".");
            if (!in.confirm("Remove ALL expired stock?")) {
                out.println("  Removal cancelled. No stock was changed.");
                return;
            }
            List<StockRemoval> removals = app.removal.removeAllExpired(remarks);
            save();
            out.println("  SUCCESS: " + removals.size() + " removal(s) recorded.");
            removals.forEach(r -> out.println("    " + r.summaryLine()));
        }
    }

    private void showReport(ReportType type) {
        out.println();
        out.print(app.reports.generate(type));
    }

    private void transactionHistory() {
        title("TRANSACTION HISTORY");
        List<Transaction> all = app.transactions.getAll();
        if (all.isEmpty()) {
            out.println("  No transactions recorded yet.");
            return;
        }
        all.forEach(t -> out.println("  " + t.summaryLine())); // polymorphism: each type describes itself
        out.println("\n  Sales: " + app.transactions.getSales().size()
                + "   Stock removals: " + app.transactions.getRemovals().size());
    }
}
