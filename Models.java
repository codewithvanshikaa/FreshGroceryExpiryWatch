import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

enum Category {
    DAIRY("Dairy"),
    BAKERY("Bakery"),
    PRODUCE("Fruits & Vegetables"),
    MEAT_FISH("Meat & Fish"),
    BEVERAGES("Beverages"),
    FROZEN("Frozen"),
    SNACKS("Snacks"),
    STAPLES("Staples"),
    OTHER("Other");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Category fromString(String text) throws InvalidInputException {
        if (text != null) {
            String t = text.trim();
            for (Category c : values()) {
                if (c.name().equalsIgnoreCase(t) || c.displayName.equalsIgnoreCase(t)) {
                    return c;
                }
            }
        }
        throw new InvalidInputException("Unknown category: '" + text + "'.");
    }

    @Override
    public String toString() {
        return displayName;
    }
}

enum StockStatus {
    EXPIRED("EXPIRED"),
    NEAR_EXPIRY("NEAR-EXPIRY"),
    SAFE("SAFE");

    private final String label;

    StockStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

enum RemovalReason {
    DAMAGED("Damaged"),
    EXPIRED("Expired");

    private final String label;

    RemovalReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

class Product {

    private final String productId;
    private String name;
    private Category category;

    public Product(String productId, String name, Category category) throws InvalidInputException {
        this.productId = InputValidator.requireId(productId, "Product ID");
        this.name = InputValidator.requireText(name, "Product name");
        this.category = InputValidator.requireNonNull(category, "Category");
    }

    public String getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && productId.equals(other.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId);
    }

    @Override
    public String toString() {
        return productId + " - " + name + " (" + category + ")";
    }
}

class Batch {

    private final String batchNumber;
    private final String productId;
    private final int receivedQuantity;
    private int availableQuantity;
    private final BigDecimal unitPurchaseCost;
    private BigDecimal unitSellingPrice;
    private final LocalDate receivedDate;
    private final LocalDate expiryDate;

    public Batch(String batchNumber, String productId, int receivedQuantity,
                 BigDecimal unitPurchaseCost, BigDecimal unitSellingPrice,
                 LocalDate receivedDate, LocalDate expiryDate) throws InvalidInputException {
        this(batchNumber, productId, receivedQuantity, receivedQuantity,
                unitPurchaseCost, unitSellingPrice, receivedDate, expiryDate);
    }

    public Batch(String batchNumber, String productId, int receivedQuantity, int availableQuantity,
                 BigDecimal unitPurchaseCost, BigDecimal unitSellingPrice,
                 LocalDate receivedDate, LocalDate expiryDate) throws InvalidInputException {
        this.batchNumber = InputValidator.requireId(batchNumber, "Batch number");
        this.productId = InputValidator.requireId(productId, "Product ID");
        this.receivedQuantity = InputValidator.requirePositive(receivedQuantity, "Received quantity");
        if (availableQuantity < 0 || availableQuantity > receivedQuantity) {
            throw new InvalidInputException("Available quantity must be between 0 and the received quantity.");
        }
        this.availableQuantity = availableQuantity;
        this.unitPurchaseCost = MoneyUtil.scale(InputValidator.requireNonNegative(unitPurchaseCost, "Unit purchase cost"));
        this.unitSellingPrice = MoneyUtil.scale(InputValidator.requireNonNegative(unitSellingPrice, "Unit selling price"));
        this.receivedDate = InputValidator.requireNonNull(receivedDate, "Received date");
        this.expiryDate = InputValidator.requireNonNull(expiryDate, "Expiry date");
        if (expiryDate.isBefore(receivedDate)) {
            throw new InvalidInputException("Expiry date cannot be before the received date.");
        }
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public String getProductId() {
        return productId;
    }

    public int getReceivedQuantity() {
        return receivedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public BigDecimal getUnitPurchaseCost() {
        return unitPurchaseCost;
    }

    public BigDecimal getUnitSellingPrice() {
        return unitSellingPrice;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    // expired only after the expiry date, on that day it is still ok
    public boolean isExpiredOn(LocalDate date) {
        return expiryDate.isBefore(date);
    }

    public boolean isOutOfStock() {
        return availableQuantity == 0;
    }

    public BigDecimal getInventoryValue() {
        return MoneyUtil.multiply(unitPurchaseCost, availableQuantity);
    }

    public void deduct(int quantity) throws InvalidInputException, InsufficientStockException {
        InputValidator.requirePositive(quantity, "Quantity");
        if (quantity > availableQuantity) {
            throw new InsufficientStockException("Batch " + batchNumber + " has only " + availableQuantity
                    + " unit(s); cannot deduct " + quantity + ".");
        }
        availableQuantity -= quantity;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Batch other && batchNumber.equals(other.batchNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchNumber);
    }

    @Override
    public String toString() {
        return batchNumber + " [" + productId + "] " + availableQuantity + "/" + receivedQuantity
                + " exp " + expiryDate;
    }
}

class SaleLine {

    private final String batchNumber;
    private final int quantity;
    private final BigDecimal unitPrice;

    public SaleLine(String batchNumber, int quantity, BigDecimal unitPrice) throws InvalidInputException {
        this.batchNumber = InputValidator.requireId(batchNumber, "Batch number");
        this.quantity = InputValidator.requirePositive(quantity, "Quantity");
        this.unitPrice = MoneyUtil.scale(InputValidator.requireNonNegative(unitPrice, "Unit price"));
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return MoneyUtil.multiply(unitPrice, quantity);
    }
}

abstract class Transaction {

    private final String transactionId;
    private final LocalDate date;

    protected Transaction(String transactionId, LocalDate date) throws InvalidInputException {
        this.transactionId = InputValidator.requireId(transactionId, "Transaction ID");
        this.date = InputValidator.requireNonNull(date, "Transaction date");
    }

    public String getTransactionId() {
        return transactionId;
    }

    public LocalDate getDate() {
        return date;
    }

    public abstract String getType();

    public abstract String summaryLine();
}

class SaleTransaction extends Transaction {

    private final String productId;
    private final String productName;
    private final List<SaleLine> lines;

    public SaleTransaction(String transactionId, LocalDate date, String productId,
                           String productName, List<SaleLine> lines) throws InvalidInputException {
        super(transactionId, date);
        this.productId = InputValidator.requireId(productId, "Product ID");
        this.productName = InputValidator.requireText(productName, "Product name");
        if (lines == null || lines.isEmpty()) {
            throw new InvalidInputException("A sale must contain at least one batch line.");
        }
        this.lines = List.copyOf(lines); // unmodifiable copy keeps history safe
    }

    public String getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public List<SaleLine> getLines() {
        return lines;
    }

    public int getTotalQuantity() {
        return lines.stream().mapToInt(SaleLine::getQuantity).sum();
    }

    public BigDecimal getTotalAmount() {
        return MoneyUtil.sum(lines.stream().map(SaleLine::getLineTotal));
    }

    @Override
    public String getType() {
        return "SALE";
    }

    @Override
    public String summaryLine() {
        String batches = lines.stream()
                .map(l -> l.getBatchNumber() + ":" + l.getQuantity())
                .collect(Collectors.joining(", "));
        return getTransactionId() + " | " + getDate() + " | SALE | " + productName
                + " x" + getTotalQuantity() + " | Rs. " + MoneyUtil.format(getTotalAmount())
                + " | batches " + batches;
    }
}

class StockRemoval extends Transaction {

    private final String productId;
    private final String batchNumber;
    private final int quantity;
    private final RemovalReason reason;
    private final BigDecimal unitCostSnapshot;
    private final String remarks;

    public StockRemoval(String transactionId, LocalDate date, String productId, String batchNumber,
                        int quantity, RemovalReason reason, BigDecimal unitCostSnapshot,
                        String remarks) throws InvalidInputException {
        super(transactionId, date);
        this.productId = InputValidator.requireId(productId, "Product ID");
        this.batchNumber = InputValidator.requireId(batchNumber, "Batch number");
        this.quantity = InputValidator.requirePositive(quantity, "Removal quantity");
        this.reason = InputValidator.requireNonNull(reason, "Removal reason");
        this.unitCostSnapshot = MoneyUtil.scale(InputValidator.requireNonNegative(unitCostSnapshot, "Unit cost"));
        this.remarks = InputValidator.optionalText(remarks, "Remarks");
    }

    public String getProductId() {
        return productId;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public int getQuantity() {
        return quantity;
    }

    public RemovalReason getReason() {
        return reason;
    }

    public BigDecimal getUnitCostSnapshot() {
        return unitCostSnapshot;
    }

    public String getRemarks() {
        return remarks;
    }

    public BigDecimal getWastageCost() {
        return MoneyUtil.multiply(unitCostSnapshot, quantity);
    }

    @Override
    public String getType() {
        return "REMOVAL";
    }

    @Override
    public String summaryLine() {
        return getTransactionId() + " | " + getDate() + " | REMOVAL (" + reason.getLabel() + ") | batch "
                + batchNumber + " x" + quantity + " | loss Rs. " + MoneyUtil.format(getWastageCost());
    }
}

class FefoComparator implements Comparator<Batch> {

    @Override
    public int compare(Batch first, Batch second) {
        // earliest expiry first, then received date, then batch number
        int result = first.getExpiryDate().compareTo(second.getExpiryDate());
        if (result != 0) {
            return result;
        }
        result = first.getReceivedDate().compareTo(second.getReceivedDate());
        if (result != 0) {
            return result;
        }
        return first.getBatchNumber().compareTo(second.getBatchNumber());
    }
}
