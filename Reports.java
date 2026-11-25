import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

enum ReportType {
    INVENTORY,
    EXPIRED,
    NEAR_EXPIRY,
    SALES,
    WASTAGE
}

interface Report {

    String getTitle();

    String generate();
}

class InventoryReport implements Report {

    private final InventoryManager inventory;
    private final ExpiryService expiry;

    public InventoryReport(InventoryManager inventory, ExpiryService expiry) {
        this.inventory = inventory;
        this.expiry = expiry;
    }

    @Override
    public String getTitle() {
        return "INVENTORY REPORT";
    }

    public int getTotalUnits() {
        return inventory.getAllBatches().stream().mapToInt(Batch::getAvailableQuantity).sum();
    }

    public BigDecimal getTotalValuation() {
        return MoneyUtil.sum(inventory.getAllBatches().stream().map(Batch::getInventoryValue));
    }

    public BigDecimal getTotalSellingValue() {
        return MoneyUtil.sum(inventory.getAllBatches().stream().map(InventoryReport::sellingValue));
    }

    private static BigDecimal sellingValue(Batch batch) {
        return MoneyUtil.multiply(batch.getUnitSellingPrice(), batch.getAvailableQuantity());
    }

    public String renderProductTotals() {
        List<Product> products = inventory.getAllProducts();
        if (products.isEmpty()) {
            return "No products in the system yet.\n";
        }
        Map<String, List<Batch>> byProduct = inventory.getAllBatches().stream()
                .collect(Collectors.groupingBy(Batch::getProductId));
        TablePrinter totals = new TablePrinter("Product ID", "Name", "Category", "Batches", "Units on hand")
                .rightAlign(3, 4);
        for (Product p : products) {
            List<Batch> batches = byProduct.getOrDefault(p.getProductId(), List.of());
            int units = batches.stream().mapToInt(Batch::getAvailableQuantity).sum();
            totals.addRow(p.getProductId(), p.getName(), p.getCategory().getDisplayName(),
                    String.valueOf(batches.size()), String.valueOf(units));
        }
        return totals.render() + "Total units on hand: " + getTotalUnits() + "\n";
    }

    public String renderBatchTable() {
        List<Batch> allBatches = inventory.getAllBatches();
        if (allBatches.isEmpty()) {
            return "No batches received yet.\n";
        }
        TablePrinter table = new TablePrinter("Batch", "Product", "Received", "Available",
                "Cost (Rs.)", "Price (Rs.)", "Received On", "Expiry", "Status")
                .rightAlign(2, 3, 4, 5);
        for (Batch b : allBatches) {
            table.addRow(b.getBatchNumber(), b.getProductId(), String.valueOf(b.getReceivedQuantity()),
                    String.valueOf(b.getAvailableQuantity()), MoneyUtil.format(b.getUnitPurchaseCost()),
                    MoneyUtil.format(b.getUnitSellingPrice()), b.getReceivedDate().toString(),
                    b.getExpiryDate().toString(), expiry.statusLabel(b));
        }
        return table.render();
    }

    @Override
    public String generate() {
        StringBuilder sb = new StringBuilder(TablePrinter.banner(getTitle()));
        sb.append("As of: ").append(expiry.today()).append('\n');

        List<Product> products = inventory.getAllProducts();
        if (products.isEmpty()) {
            return sb.append("\nNo products in the system yet.\n").toString();
        }
        Map<String, List<Batch>> byProduct = inventory.getAllBatches().stream()
                .collect(Collectors.groupingBy(Batch::getProductId));

        // totals per product
        sb.append(TablePrinter.section("1. Total inventory by product"));
        sb.append(renderProductTotals());

        // batch wise
        sb.append(TablePrinter.section("2. Batch-wise inventory"));
        sb.append(renderBatchTable());

        // valuation
        sb.append(TablePrinter.section("3. Product-wise inventory valuation"));
        TablePrinter valuation = new TablePrinter("Product ID", "Name", "Units", "Cost value (Rs.)",
                "Selling value (Rs.)").rightAlign(2, 3, 4);
        for (Product p : products) {
            List<Batch> batches = byProduct.getOrDefault(p.getProductId(), List.of());
            int units = batches.stream().mapToInt(Batch::getAvailableQuantity).sum();
            BigDecimal cost = MoneyUtil.sum(batches.stream().map(Batch::getInventoryValue));
            BigDecimal selling = MoneyUtil.sum(batches.stream().map(InventoryReport::sellingValue));
            valuation.addRow(p.getProductId(), p.getName(), String.valueOf(units),
                    MoneyUtil.format(cost), MoneyUtil.format(selling));
        }
        sb.append(valuation.render());
        sb.append("TOTAL valuation at cost   : Rs. ").append(MoneyUtil.format(getTotalValuation())).append('\n');
        sb.append("TOTAL valuation at selling: Rs. ").append(MoneyUtil.format(getTotalSellingValue())).append('\n');
        sb.append("(Includes expired stock until it is removed.)\n");
        return sb.toString();
    }
}

class ExpiryReport implements Report {

    private final InventoryManager inventory;
    private final ExpiryService expiry;
    private final ConfigService config;
    private final StockStatus focus;

    public ExpiryReport(InventoryManager inventory, ExpiryService expiry, ConfigService config, StockStatus focus) {
        if (focus != StockStatus.EXPIRED && focus != StockStatus.NEAR_EXPIRY) {
            throw new IllegalArgumentException("ExpiryReport supports only EXPIRED and NEAR_EXPIRY.");
        }
        this.inventory = inventory;
        this.expiry = expiry;
        this.config = config;
        this.focus = focus;
    }

    @Override
    public String getTitle() {
        return focus == StockStatus.EXPIRED ? "EXPIRED STOCK REPORT" : "NEAR-EXPIRY STOCK REPORT";
    }

    @Override
    public String generate() {
        boolean expiredReport = focus == StockStatus.EXPIRED;
        StringBuilder sb = new StringBuilder(TablePrinter.banner(getTitle()));
        sb.append("As of: ").append(expiry.today()).append('\n');
        if (!expiredReport) {
            sb.append("Alert threshold: ").append(config.getNearExpiryDays()).append(" day(s)\n");
        }

        List<Batch> batches = expiry.getBatchesByStatus(focus);
        if (batches.isEmpty()) {
            return sb.append(expiredReport ? "\nNo expired stock found.\n" : "\nNo near-expiry stock found.\n")
                    .toString();
        }

        TablePrinter table = new TablePrinter("Batch", "Product", "Name", "Units", "Expiry",
                expiredReport ? "Days overdue" : "Days left", "Unit cost (Rs.)", "Cost value (Rs.)")
                .rightAlign(3, 5, 6, 7);
        for (Batch b : batches) {
            long days = expiry.daysUntilExpiry(b);
            table.addRow(b.getBatchNumber(), b.getProductId(), inventory.getProductName(b.getProductId()),
                    String.valueOf(b.getAvailableQuantity()), b.getExpiryDate().toString(),
                    String.valueOf(expiredReport ? -days : days),
                    MoneyUtil.format(b.getUnitPurchaseCost()), MoneyUtil.format(b.getInventoryValue()));
        }
        sb.append('\n').append(table.render());

        int units = batches.stream().mapToInt(Batch::getAvailableQuantity).sum();
        BigDecimal value = MoneyUtil.sum(batches.stream().map(Batch::getInventoryValue));
        sb.append("Batches: ").append(batches.size()).append("   Units: ").append(units)
                .append("   Value at cost: Rs. ").append(MoneyUtil.format(value)).append('\n');
        return sb.toString();
    }
}

class SalesReport implements Report {

    private final TransactionManager transactions;

    public SalesReport(TransactionManager transactions) {
        this.transactions = transactions;
    }

    @Override
    public String getTitle() {
        return "SALES REPORT";
    }

    public int getSaleCount() {
        return transactions.getSales().size();
    }

    public int getTotalUnitsSold() {
        return transactions.getSales().stream().mapToInt(SaleTransaction::getTotalQuantity).sum();
    }

    public BigDecimal getTotalRevenue() {
        return MoneyUtil.sum(transactions.getSales().stream().map(SaleTransaction::getTotalAmount));
    }

    @Override
    public String generate() {
        StringBuilder sb = new StringBuilder(TablePrinter.banner(getTitle()));
        List<SaleTransaction> sales = transactions.getSales();
        if (sales.isEmpty()) {
            return sb.append("\nNo sales recorded yet.\n").toString();
        }

        sb.append(TablePrinter.section("1. Sales history (batch-wise deductions)"));
        TablePrinter history = new TablePrinter("Sale ID", "Date", "Product", "Name", "Qty",
                "Amount (Rs.)", "Batches (batch:qty)").rightAlign(4, 5);
        for (SaleTransaction s : sales) {
            String batches = s.getLines().stream()
                    .map(l -> l.getBatchNumber() + ":" + l.getQuantity())
                    .collect(Collectors.joining(", "));
            history.addRow(s.getTransactionId(), s.getDate().toString(), s.getProductId(), s.getProductName(),
                    String.valueOf(s.getTotalQuantity()), MoneyUtil.format(s.getTotalAmount()), batches);
        }
        sb.append(history.render());

        sb.append(TablePrinter.section("2. Sales by product"));
        Map<String, List<SaleTransaction>> byProduct = sales.stream()
                .collect(Collectors.groupingBy(SaleTransaction::getProductId, TreeMap::new, Collectors.toList()));
        TablePrinter perProduct = new TablePrinter("Product", "Name", "Sales", "Units sold", "Revenue (Rs.)")
                .rightAlign(2, 3, 4);
        byProduct.forEach((productId, list) -> perProduct.addRow(productId, list.get(0).getProductName(),
                String.valueOf(list.size()),
                String.valueOf(list.stream().mapToInt(SaleTransaction::getTotalQuantity).sum()),
                MoneyUtil.format(MoneyUtil.sum(list.stream().map(SaleTransaction::getTotalAmount)))));
        sb.append(perProduct.render());

        sb.append("\nTotal sales: ").append(getSaleCount())
                .append("   Units sold: ").append(getTotalUnitsSold())
                .append("   Total revenue: Rs. ").append(MoneyUtil.format(getTotalRevenue())).append('\n');
        return sb.toString();
    }
}

class WastageReport implements Report {

    private final TransactionManager transactions;

    public WastageReport(TransactionManager transactions) {
        this.transactions = transactions;
    }

    @Override
    public String getTitle() {
        return "WASTAGE REPORT (STOCK REMOVALS)";
    }

    public int getTotalWastageQuantity() {
        return transactions.getRemovals().stream().mapToInt(StockRemoval::getQuantity).sum();
    }

    public BigDecimal getTotalWastageCost() {
        return MoneyUtil.sum(transactions.getRemovals().stream().map(StockRemoval::getWastageCost));
    }

    @Override
    public String generate() {
        StringBuilder sb = new StringBuilder(TablePrinter.banner(getTitle()));
        List<StockRemoval> removals = transactions.getRemovals();
        if (removals.isEmpty()) {
            return sb.append("\nNo stock removals recorded yet.\nTotal wastage quantity: 0\n")
                    .append("Total wastage cost    : Rs. ").append(MoneyUtil.format(MoneyUtil.ZERO)).append('\n')
                    .toString();
        }

        sb.append(TablePrinter.section("1. Stock removal history"));
        TablePrinter history = new TablePrinter("ID", "Date", "Batch", "Product", "Reason", "Qty",
                "Unit cost (Rs.)", "Wastage (Rs.)", "Remarks").rightAlign(5, 6, 7);
        for (StockRemoval r : removals) {
            history.addRow(r.getTransactionId(), r.getDate().toString(), r.getBatchNumber(), r.getProductId(),
                    r.getReason().getLabel(), String.valueOf(r.getQuantity()),
                    MoneyUtil.format(r.getUnitCostSnapshot()), MoneyUtil.format(r.getWastageCost()),
                    r.getRemarks());
        }
        sb.append(history.render());

        sb.append(TablePrinter.section("2. Wastage by reason"));
        Map<RemovalReason, List<StockRemoval>> byReason = removals.stream()
                .collect(Collectors.groupingBy(StockRemoval::getReason,
                        () -> new EnumMap<>(RemovalReason.class), Collectors.toList()));
        TablePrinter reasons = new TablePrinter("Reason", "Quantity", "Cost (Rs.)").rightAlign(1, 2);
        for (RemovalReason reason : RemovalReason.values()) {
            List<StockRemoval> list = byReason.getOrDefault(reason, List.of());
            reasons.addRow(reason.getLabel(),
                    String.valueOf(list.stream().mapToInt(StockRemoval::getQuantity).sum()),
                    MoneyUtil.format(MoneyUtil.sum(list.stream().map(StockRemoval::getWastageCost))));
        }
        sb.append(reasons.render());

        sb.append("\nTotal wastage quantity: ").append(getTotalWastageQuantity()).append('\n');
        sb.append("Total wastage cost    : Rs. ").append(MoneyUtil.format(getTotalWastageCost())).append('\n');
        return sb.toString();
    }
}

class ReportGenerator {

    private final Map<ReportType, Report> reports = new EnumMap<>(ReportType.class);

    public ReportGenerator(InventoryManager inventory, ExpiryService expiry, ConfigService config,
                           TransactionManager transactions) {
        reports.put(ReportType.INVENTORY, new InventoryReport(inventory, expiry));
        reports.put(ReportType.EXPIRED, new ExpiryReport(inventory, expiry, config, StockStatus.EXPIRED));
        reports.put(ReportType.NEAR_EXPIRY, new ExpiryReport(inventory, expiry, config, StockStatus.NEAR_EXPIRY));
        reports.put(ReportType.SALES, new SalesReport(transactions));
        reports.put(ReportType.WASTAGE, new WastageReport(transactions));
    }

    public Report get(ReportType type) {
        return reports.get(type);
    }

    public String generate(ReportType type) {
        return reports.get(type).generate();
    }
}
