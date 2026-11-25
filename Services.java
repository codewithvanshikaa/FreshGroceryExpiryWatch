import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

class InventoryManager {

    // product id -> product, batch no -> batch, product id -> its batches
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Batch> batchesByNumber = new HashMap<>();
    private final Map<String, List<Batch>> batchesByProduct = new HashMap<>();

    private final Clock clock;
    private final FefoComparator fefoComparator = new FefoComparator();

    public InventoryManager(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public void addProduct(Product product) throws DuplicateEntryException {
        if (products.containsKey(product.getProductId())) {
            throw new DuplicateEntryException("Product ID " + product.getProductId() + " already exists.");
        }
        products.put(product.getProductId(), product);
        batchesByProduct.put(product.getProductId(), new ArrayList<>());
    }

    public Product getProduct(String productId) throws NotFoundException {
        Product product = products.get(normalise(productId));
        if (product == null) {
            throw new NotFoundException("Product " + productId + " not found.");
        }
        return product;
    }

    public String getProductName(String productId) {
        Product product = products.get(normalise(productId));
        return product == null ? productId : product.getName();
    }

    public boolean productExists(String productId) {
        return products.containsKey(normalise(productId));
    }

    public List<Product> getAllProducts() {
        return products.values().stream()
                .sorted(Comparator.comparing(Product::getProductId))
                .toList();
    }

    public void restore(Collection<Product> productList, Collection<Batch> batchList)
            throws DuplicateEntryException, NotFoundException {
        Map<String, Product> newProducts = new HashMap<>();
        Map<String, Batch> newBatches = new HashMap<>();
        Map<String, List<Batch>> newByProduct = new HashMap<>();
        for (Product p : productList) {
            if (newProducts.put(p.getProductId(), p) != null) {
                throw new DuplicateEntryException("Product ID " + p.getProductId() + " appears twice in the saved data.");
            }
            newByProduct.put(p.getProductId(), new ArrayList<>());
        }
        for (Batch b : batchList) {
            if (!newProducts.containsKey(b.getProductId())) {
                throw new NotFoundException("Batch " + b.getBatchNumber() + " refers to unknown product "
                        + b.getProductId() + ".");
            }
            if (newBatches.put(b.getBatchNumber(), b) != null) {
                throw new DuplicateEntryException("Batch number " + b.getBatchNumber() + " appears twice in the saved data.");
            }
            newByProduct.get(b.getProductId()).add(b);
        }
        products.clear();
        products.putAll(newProducts);
        batchesByNumber.clear();
        batchesByNumber.putAll(newBatches);
        batchesByProduct.clear();
        batchesByProduct.putAll(newByProduct);
    }

    public boolean isAlreadyExpired(Batch batch) {
        return batch.isExpiredOn(today());
    }

    public void addBatch(Batch batch, boolean confirmPastExpiry)
            throws NotFoundException, DuplicateEntryException, InvalidInputException {
        if (!products.containsKey(batch.getProductId())) {
            throw new NotFoundException("Product " + batch.getProductId() + " not found. Add the product first.");
        }
        if (batchesByNumber.containsKey(batch.getBatchNumber())) {
            throw new DuplicateEntryException("Batch number " + batch.getBatchNumber() + " already exists.");
        }
        if (batch.getReceivedDate().isAfter(today())) {
            throw new InvalidInputException("Received date cannot be in the future.");
        }
        if (isAlreadyExpired(batch) && !confirmPastExpiry) {
            throw new InvalidInputException("Batch " + batch.getBatchNumber()
                    + " is already expired (" + batch.getExpiryDate() + "). Confirmation is required to add it.");
        }
        batchesByNumber.put(batch.getBatchNumber(), batch);
        batchesByProduct.get(batch.getProductId()).add(batch);
    }

    public Batch findBatch(String batchNumber) throws NotFoundException {
        Batch batch = batchesByNumber.get(normalise(batchNumber));
        if (batch == null) {
            throw new NotFoundException("Batch " + batchNumber + " not found.");
        }
        return batch;
    }

    public boolean batchExists(String batchNumber) {
        return batchesByNumber.containsKey(normalise(batchNumber));
    }

    public List<Batch> getBatchesForProduct(String productId) throws NotFoundException {
        Product product = getProduct(productId);
        return batchesByProduct.get(product.getProductId()).stream()
                .sorted(fefoComparator)
                .toList();
    }

    public List<Batch> getAllBatches() {
        return batchesByNumber.values().stream()
                .sorted(Comparator.comparing(Batch::getProductId).thenComparing(fefoComparator))
                .toList();
    }

    public int getTotalAvailable(String productId) throws NotFoundException {
        Product product = getProduct(productId);
        return batchesByProduct.get(product.getProductId()).stream()
                .mapToInt(Batch::getAvailableQuantity)
                .sum();
    }

    private static String normalise(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }
}

class TransactionManager {

    private static final Comparator<Transaction> HISTORY_ORDER = Comparator
            .comparing(Transaction::getDate)
            .thenComparing(Transaction::getType, Comparator.reverseOrder()) // SALE before REMOVAL
            .thenComparing(Transaction::getTransactionId);

    private final Map<String, Transaction> transactions = new HashMap<>();

    public String nextSaleId() {
        return nextId("S");
    }

    public String nextRemovalId() {
        return nextId("R");
    }

    private String nextId(String prefix) {
        int highest = transactions.keySet().stream()
                .filter(id -> id.startsWith(prefix + "-"))
                .mapToInt(TransactionManager::numberPart)
                .max()
                .orElse(0);
        return String.format("%s-%04d", prefix, highest + 1);
    }

    private static int numberPart(String id) {
        try {
            return Integer.parseInt(id.substring(2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void record(Transaction transaction) throws DuplicateEntryException {
        if (transactions.containsKey(transaction.getTransactionId())) {
            throw new DuplicateEntryException("Transaction " + transaction.getTransactionId() + " already exists.");
        }
        transactions.put(transaction.getTransactionId(), transaction);
    }

    public void restore(Collection<Transaction> loaded) throws DuplicateEntryException {
        Map<String, Transaction> fresh = new HashMap<>();
        for (Transaction t : loaded) {
            if (fresh.put(t.getTransactionId(), t) != null) {
                throw new DuplicateEntryException("Transaction " + t.getTransactionId() + " appears twice in the saved data.");
            }
        }
        transactions.clear();
        transactions.putAll(fresh);
    }

    public List<Transaction> getAll() {
        return transactions.values().stream().sorted(HISTORY_ORDER).toList();
    }

    public List<SaleTransaction> getSales() {
        return getAll().stream()
                .filter(SaleTransaction.class::isInstance)
                .map(SaleTransaction.class::cast)
                .toList();
    }

    public List<StockRemoval> getRemovals() {
        return getAll().stream()
                .filter(StockRemoval.class::isInstance)
                .map(StockRemoval.class::cast)
                .toList();
    }
}

class ConfigService {

    public static final int DEFAULT_NEAR_EXPIRY_DAYS = 7;
    public static final int MAX_NEAR_EXPIRY_DAYS = 365;

    private int nearExpiryDays = DEFAULT_NEAR_EXPIRY_DAYS;

    public int getNearExpiryDays() {
        return nearExpiryDays;
    }

    public void setNearExpiryDays(int days) throws InvalidInputException {
        if (days < 1 || days > MAX_NEAR_EXPIRY_DAYS) {
            throw new InvalidInputException("Near-expiry days must be between 1 and " + MAX_NEAR_EXPIRY_DAYS + ".");
        }
        this.nearExpiryDays = days;
    }
}

class ExpiryService {

    private final InventoryManager inventory;
    private final ConfigService config;
    private final FefoComparator fefoComparator = new FefoComparator();

    public ExpiryService(InventoryManager inventory, ConfigService config) {
        this.inventory = inventory;
        this.config = config;
    }

    public LocalDate today() {
        return inventory.today();
    }

    public long daysUntilExpiry(Batch batch) {
        return ChronoUnit.DAYS.between(today(), batch.getExpiryDate());
    }

    public StockStatus classify(Batch batch) {
        if (batch.isExpiredOn(today())) {
            return StockStatus.EXPIRED;
        }
        if (daysUntilExpiry(batch) <= config.getNearExpiryDays()) {
            return StockStatus.NEAR_EXPIRY;
        }
        return StockStatus.SAFE;
    }

    public String statusLabel(Batch batch) {
        if (batch.isOutOfStock()) {
            return "[EMPTY]";
        }
        return switch (classify(batch)) {
            case EXPIRED -> "[EXPIRED]";
            case NEAR_EXPIRY -> "[NEAR " + daysUntilExpiry(batch) + "d]";
            case SAFE -> "[SAFE]";
        };
    }

    public List<Batch> getBatchesByStatus(StockStatus status) {
        return inventory.getAllBatches().stream()
                .filter(b -> !b.isOutOfStock())
                .filter(b -> classify(b) == status)
                .sorted(fefoComparator)
                .toList();
    }

    public List<Batch> getExpiredBatches() {
        return getBatchesByStatus(StockStatus.EXPIRED);
    }

    public List<Batch> getNearExpiryBatches() {
        return getBatchesByStatus(StockStatus.NEAR_EXPIRY);
    }

    public Map<StockStatus, Long> countByStatus() {
        Map<StockStatus, Long> counts = new EnumMap<>(StockStatus.class);
        for (StockStatus status : StockStatus.values()) {
            counts.put(status, 0L);
        }
        inventory.getAllBatches().stream()
                .filter(b -> !b.isOutOfStock())
                .collect(Collectors.groupingBy(this::classify, Collectors.counting()))
                .forEach(counts::put);
        return counts;
    }

    public BigDecimal getExpiredStockCost() {
        return MoneyUtil.sum(getExpiredBatches().stream().map(Batch::getInventoryValue));
    }
}

class SalesManager {

    private final InventoryManager inventory;
    private final TransactionManager transactions;

    public SalesManager(InventoryManager inventory, TransactionManager transactions) {
        this.inventory = inventory;
        this.transactions = transactions;
    }

    public List<SaleLine> planSale(String productId, int quantity)
            throws NotFoundException, InvalidInputException, InsufficientStockException {
        InputValidator.requirePositive(quantity, "Sale quantity");
        Product product = inventory.getProduct(productId);
        LocalDate today = inventory.today();

        List<Batch> allBatches = inventory.getBatchesForProduct(product.getProductId()); // already FEFO sorted
        List<Batch> sellable = allBatches.stream()
                .filter(b -> !b.isOutOfStock())
                .filter(b -> !b.isExpiredOn(today))
                .toList();

        int sellableUnits = sellable.stream().mapToInt(Batch::getAvailableQuantity).sum();
        if (quantity > sellableUnits) {
            int expiredUnits = allBatches.stream()
                    .filter(b -> b.isExpiredOn(today))
                    .mapToInt(Batch::getAvailableQuantity)
                    .sum();
            String message = "Cannot sell " + quantity + " unit(s) of " + product.getName()
                    + ": only " + sellableUnits + " sellable unit(s) available.";
            if (expiredUnits > 0) {
                message += " (" + expiredUnits + " expired unit(s) cannot be sold.)";
            }
            throw new InsufficientStockException(message);
        }

        List<SaleLine> lines = new ArrayList<>();
        int remaining = quantity;
        for (Batch batch : sellable) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, batch.getAvailableQuantity());
            lines.add(new SaleLine(batch.getBatchNumber(), take, batch.getUnitSellingPrice()));
            remaining -= take;
        }
        return lines;
    }

    public List<String> findBelowCostBatchNumbers(List<SaleLine> plan) throws NotFoundException {
        List<String> result = new ArrayList<>();
        for (SaleLine line : plan) {
            Batch batch = inventory.findBatch(line.getBatchNumber());
            if (line.getUnitPrice().compareTo(batch.getUnitPurchaseCost()) < 0) {
                result.add(batch.getBatchNumber());
            }
        }
        return result;
    }

    public SaleTransaction recordSale(String productId, int quantity) throws InventoryException {
        List<SaleLine> lines = planSale(productId, quantity);
        Product product = inventory.getProduct(productId);

        // create the sale first so invalid data is caught before stock is reduced
        SaleTransaction sale = new SaleTransaction(transactions.nextSaleId(), inventory.today(),
                product.getProductId(), product.getName(), lines);

        for (SaleLine line : lines) {
            inventory.findBatch(line.getBatchNumber()).deduct(line.getQuantity());
        }
        transactions.record(sale);
        return sale;
    }
}

class StockRemovalService {

    private final InventoryManager inventory;
    private final TransactionManager transactions;

    public StockRemovalService(InventoryManager inventory, TransactionManager transactions) {
        this.inventory = inventory;
        this.transactions = transactions;
    }

    public StockRemoval removeDamaged(String batchNumber, int quantity, String remarks) throws InventoryException {
        return remove(batchNumber, quantity, RemovalReason.DAMAGED, remarks);
    }

    public StockRemoval removeExpired(String batchNumber, int quantity, String remarks) throws InventoryException {
        return remove(batchNumber, quantity, RemovalReason.EXPIRED, remarks);
    }

    public List<StockRemoval> removeAllExpired(String remarks) throws InventoryException {
        LocalDate today = inventory.today();
        List<Batch> expired = inventory.getAllBatches().stream()
                .filter(b -> b.isExpiredOn(today))
                .filter(b -> !b.isOutOfStock())
                .toList();
        List<StockRemoval> removals = new ArrayList<>();
        for (Batch batch : expired) {
            removals.add(remove(batch.getBatchNumber(), batch.getAvailableQuantity(), RemovalReason.EXPIRED, remarks));
        }
        return removals;
    }

    private StockRemoval remove(String batchNumber, int quantity, RemovalReason reason, String remarks)
            throws InventoryException {
        InputValidator.requirePositive(quantity, "Removal quantity");
        Batch batch = inventory.findBatch(batchNumber);
        LocalDate today = inventory.today();

        if (batch.isOutOfStock()) {
            throw new InvalidRemovalException("Batch " + batch.getBatchNumber() + " has no stock left to remove.");
        }
        if (quantity > batch.getAvailableQuantity()) {
            throw new InvalidRemovalException("Cannot remove " + quantity + " unit(s): batch "
                    + batch.getBatchNumber() + " has only " + batch.getAvailableQuantity() + ".");
        }
        if (reason == RemovalReason.EXPIRED && !batch.isExpiredOn(today)) {
            throw new InvalidRemovalException("Batch " + batch.getBatchNumber() + " expires on "
                    + batch.getExpiryDate() + " and is not expired yet. Use damaged-stock removal if it is damaged.");
        }

        StockRemoval removal = new StockRemoval(transactions.nextRemovalId(), today, batch.getProductId(),
                batch.getBatchNumber(), quantity, reason, batch.getUnitPurchaseCost(), remarks);
        batch.deduct(quantity);
        transactions.record(removal);
        return removal;
    }
}

class PersistenceService {

    private final Path dataDir;
    private final InventoryManager inventory;
    private final TransactionManager transactions;
    private final ConfigService config;

    private final ProductCsvRepository productRepo;
    private final BatchCsvRepository batchRepo;
    private final SaleCsvRepository saleRepo;
    private final RemovalCsvRepository removalRepo;
    private final ConfigRepository configRepo;

    public PersistenceService(Path dataDir, InventoryManager inventory,
                              TransactionManager transactions, ConfigService config) {
        this.dataDir = dataDir;
        this.inventory = inventory;
        this.transactions = transactions;
        this.config = config;
        this.productRepo = new ProductCsvRepository(dataDir.resolve("products.csv"));
        this.batchRepo = new BatchCsvRepository(dataDir.resolve("batches.csv"));
        this.saleRepo = new SaleCsvRepository(dataDir.resolve("sales.csv"));
        this.removalRepo = new RemovalCsvRepository(dataDir.resolve("removals.csv"));
        this.configRepo = new ConfigRepository(dataDir.resolve("config.properties"));
    }

    public Path getDataDir() {
        return dataDir;
    }

    public boolean hasSavedData() {
        return Files.exists(productRepo.getFile()) || Files.exists(batchRepo.getFile());
    }

    public List<String> load() throws PersistenceException {
        // read everything first, nothing is changed yet
        List<Product> products = productRepo.loadAll();
        List<Batch> batches = batchRepo.loadAll();
        List<SaleTransaction> sales = saleRepo.loadAll();
        List<StockRemoval> removals = removalRepo.loadAll();
        OptionalInt days = configRepo.loadNearExpiryDays();
        checkBatchReferences(batches, sales, removals);

        try {
            if (days.isPresent()) {
                config.setNearExpiryDays(days.getAsInt());
            }
            inventory.restore(products, batches);
            List<Transaction> all = new ArrayList<>(sales);
            all.addAll(removals);
            transactions.restore(all);
        } catch (InventoryException e) {
            throw new PersistenceException("Saved data is inconsistent: " + e.getMessage(), e);
        }
        return ledgerWarnings(batches, sales, removals);
    }

    private void checkBatchReferences(List<Batch> batches, List<SaleTransaction> sales,
                                      List<StockRemoval> removals) throws PersistenceException {
        Set<String> known = batches.stream().map(Batch::getBatchNumber).collect(Collectors.toSet());
        for (SaleTransaction sale : sales) {
            for (SaleLine line : sale.getLines()) {
                if (!known.contains(line.getBatchNumber())) {
                    throw new PersistenceException("sales.csv: sale " + sale.getTransactionId()
                            + " refers to unknown batch " + line.getBatchNumber() + ".");
                }
            }
        }
        for (StockRemoval removal : removals) {
            if (!known.contains(removal.getBatchNumber())) {
                throw new PersistenceException("removals.csv: removal " + removal.getTransactionId()
                        + " refers to unknown batch " + removal.getBatchNumber() + ".");
            }
        }
    }

    private List<String> ledgerWarnings(List<Batch> batches, List<SaleTransaction> sales,
                                        List<StockRemoval> removals) {
        Map<String, Integer> sold = sales.stream()
                .flatMap(s -> s.getLines().stream())
                .collect(Collectors.groupingBy(SaleLine::getBatchNumber, Collectors.summingInt(SaleLine::getQuantity)));
        Map<String, Integer> removed = removals.stream()
                .collect(Collectors.groupingBy(StockRemoval::getBatchNumber, Collectors.summingInt(StockRemoval::getQuantity)));
        List<String> warnings = new ArrayList<>();
        for (Batch b : batches) {
            int soldQty = sold.getOrDefault(b.getBatchNumber(), 0);
            int removedQty = removed.getOrDefault(b.getBatchNumber(), 0);
            int accounted = b.getAvailableQuantity() + soldQty + removedQty;
            if (accounted != b.getReceivedQuantity()) {
                warnings.add("Batch " + b.getBatchNumber() + ": received " + b.getReceivedQuantity()
                        + " but available " + b.getAvailableQuantity() + " + sold " + soldQty
                        + " + removed " + removedQty + " = " + accounted + " (stock history does not add up).");
            }
        }
        return warnings;
    }

    public void save() throws PersistenceException {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new PersistenceException("Could not create data folder " + dataDir + ": " + e.getMessage(), e);
        }
        productRepo.saveAll(inventory.getAllProducts());
        batchRepo.saveAll(inventory.getAllBatches());
        saleRepo.saveAll(transactions.getSales());
        removalRepo.saveAll(transactions.getRemovals());
        configRepo.save(config.getNearExpiryDays());
    }
}

class SampleDataLoader {

    private final InventoryManager inventory;
    private final SalesManager sales;
    private final StockRemovalService removals;

    public SampleDataLoader(InventoryManager inventory, SalesManager sales, StockRemovalService removals) {
        this.inventory = inventory;
        this.sales = sales;
        this.removals = removals;
    }

    public void load() throws InventoryException {
        inventory.addProduct(new Product("MILK01", "Full Cream Milk 1L", Category.DAIRY));
        inventory.addProduct(new Product("CURD01", "Fresh Curd 400g", Category.DAIRY));
        inventory.addProduct(new Product("PANEER01", "Paneer 200g", Category.DAIRY));
        inventory.addProduct(new Product("BREAD01", "Brown Bread", Category.BAKERY));
        inventory.addProduct(new Product("TOMATO01", "Tomatoes 1kg", Category.PRODUCE));
        inventory.addProduct(new Product("SPINACH01", "Spinach Bunch", Category.PRODUCE));
        inventory.addProduct(new Product("JUICE01", "Orange Juice 1L", Category.BEVERAGES));
        inventory.addProduct(new Product("ICECREAM01", "Vanilla Ice Cream Tub", Category.FROZEN));
        inventory.addProduct(new Product("CHIPS01", "Potato Chips 100g", Category.SNACKS));
        inventory.addProduct(new Product("RICE01", "Basmati Rice 5kg", Category.STAPLES));

        // batch no, product, qty, cost, price, received (days ago), expiry (days from today)
        batch("B-MILK-001", "MILK01", 25, "48.00", "56.00", -8, -1);   // expired
        batch("B-MILK-002", "MILK01", 40, "49.00", "57.00", -5, 2);    // near expiry
        batch("B-MILK-003", "MILK01", 60, "50.00", "58.00", -1, 9);    // safe
        batch("B-CURD-001", "CURD01", 30, "28.00", "35.00", -10, 3);   // near expiry
        batch("B-CURD-002", "CURD01", 40, "29.00", "36.00", -3, 14);   // safe
        batch("B-PANEER-001", "PANEER01", 25, "70.00", "90.00", -9, 6);// near expiry
        batch("B-BREAD-001", "BREAD01", 12, "30.00", "40.00", -7, -2); // expired
        batch("B-BREAD-002", "BREAD01", 35, "31.00", "42.00", -2, 3);  // near expiry
        batch("B-TOMATO-001", "TOMATO01", 50, "22.50", "30.00", -4, 5);// near expiry
        batch("B-SPINACH-001", "SPINACH01", 20, "12.00", "20.00", -3, 1); // near expiry
        batch("B-JUICE-001", "JUICE01", 48, "85.00", "110.00", -30, 90);
        batch("B-ICE-001", "ICECREAM01", 18, "150.00", "199.00", -20, 120);
        batch("B-CHIPS-001", "CHIPS01", 100, "8.50", "10.00", -15, 60);
        batch("B-CHIPS-002", "CHIPS01", 50, "8.50", "10.00", -10, 60); // same expiry as CHIPS-001 (tie)
        batch("B-RICE-001", "RICE01", 30, "380.00", "450.00", -40, 300);

        sales.recordSale("MILK01", 50);   // FEFO: 40 from B-MILK-002, 10 from B-MILK-003 (expired batch skipped)
        sales.recordSale("CURD01", 15);
        sales.recordSale("CHIPS01", 30);
        removals.removeExpired("B-BREAD-001", 5, "past expiry");
        removals.removeDamaged("B-JUICE-001", 2, "bottles cracked in transit");
        removals.removeDamaged("B-TOMATO-001", 6, "bruised, unsellable");
    }

    private void batch(String number, String productId, int qty, String cost, String price,
                       int receivedOffsetDays, int expiryOffsetDays) throws InventoryException {
        LocalDate today = inventory.today();
        inventory.addBatch(new Batch(number, productId, qty, new BigDecimal(cost), new BigDecimal(price),
                today.plusDays(receivedOffsetDays), today.plusDays(expiryOffsetDays)), true);
    }
}

// creates all the services in one place and connects them
final class AppServices {

    public final Clock clock;
    public final InventoryManager inventory;
    public final TransactionManager transactions;
    public final ConfigService config;
    public final ExpiryService expiry;
    public final SalesManager sales;
    public final StockRemovalService removal;
    public final ReportGenerator reports;
    public final PersistenceService store;
    public final SampleDataLoader sampleLoader;

    private AppServices(Clock clock, Path dataDir) {
        this.clock = clock;
        this.inventory = new InventoryManager(clock);
        this.transactions = new TransactionManager();
        this.config = new ConfigService();
        this.expiry = new ExpiryService(inventory, config);
        this.sales = new SalesManager(inventory, transactions);
        this.removal = new StockRemovalService(inventory, transactions);
        this.reports = new ReportGenerator(inventory, expiry, config, transactions);
        this.store = new PersistenceService(dataDir, inventory, transactions, config);
        this.sampleLoader = new SampleDataLoader(inventory, sales, removal);
    }

    public static AppServices create(Clock clock, Path dataDir) {
        return new AppServices(clock, dataDir);
    }
}
