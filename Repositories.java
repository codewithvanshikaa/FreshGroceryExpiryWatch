import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;

abstract class CsvRepository<T> {

    protected record Row(int lineNumber, String[] fields) {
    }

    private final Path file;
    private final String[] header;

    protected CsvRepository(Path file, String... header) {
        this.file = file;
        this.header = header.clone();
    }

    public Path getFile() {
        return file;
    }

    protected abstract List<String[]> toRows(T item);

    protected abstract List<T> fromRows(List<Row> rows) throws PersistenceException;

    public List<T> loadAll() throws PersistenceException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("Could not read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        int index = 0;
        while (index < lines.size() && lines.get(index).isBlank()) {
            index++;
        }
        if (index == lines.size()) {
            return List.of(); // empty file
        }
        String[] foundHeader = parse(stripBom(lines.get(index)), index + 1);
        if (!Arrays.equals(foundHeader, header)) {
            throw new PersistenceException(file.getFileName() + " line " + (index + 1)
                    + ": unexpected header. Expected: " + CsvUtil.formatLine(header));
        }
        List<Row> rows = new ArrayList<>();
        for (int n = index + 1; n < lines.size(); n++) {
            if (lines.get(n).isBlank()) {
                continue;
            }
            String[] fields = parse(lines.get(n), n + 1);
            if (fields.length != header.length) {
                throw new PersistenceException(file.getFileName() + " line " + (n + 1) + ": expected "
                        + header.length + " columns but found " + fields.length + ".");
            }
            rows.add(new Row(n + 1, fields));
        }
        return fromRows(rows);
    }

    private String[] parse(String line, int lineNumber) throws PersistenceException {
        try {
            return CsvUtil.parseLine(line);
        } catch (IllegalArgumentException e) {
            throw new PersistenceException(file.getFileName() + " line " + lineNumber + ": " + e.getMessage() + ".", e);
        }
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    public void saveAll(Collection<T> items) throws PersistenceException {
        StringBuilder sb = new StringBuilder(CsvUtil.formatLine(header)).append('\n');
        for (T item : items) {
            for (String[] row : toRows(item)) {
                sb.append(CsvUtil.formatLine(row)).append('\n');
            }
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("Could not write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    protected PersistenceException error(Row row, String message) {
        return new PersistenceException(file.getFileName() + " line " + row.lineNumber() + ": " + message);
    }

    protected int intField(Row row, int index, String name) throws PersistenceException {
        try {
            return Integer.parseInt(row.fields()[index].trim());
        } catch (NumberFormatException e) {
            throw error(row, name + " must be a whole number but was '" + row.fields()[index] + "'.");
        }
    }

    protected BigDecimal moneyField(Row row, int index, String name) throws PersistenceException {
        try {
            return new BigDecimal(row.fields()[index].trim());
        } catch (NumberFormatException e) {
            throw error(row, name + " must be a number but was '" + row.fields()[index] + "'.");
        }
    }

    protected LocalDate dateField(Row row, int index, String name) throws PersistenceException {
        try {
            return LocalDate.parse(row.fields()[index].trim());
        } catch (DateTimeParseException e) {
            throw error(row, name + " must be a date like 2026-11-21 but was '" + row.fields()[index] + "'.");
        }
    }
}

class ProductCsvRepository extends CsvRepository<Product> {

    public ProductCsvRepository(Path file) {
        super(file, "productId", "name", "category");
    }

    @Override
    protected List<String[]> toRows(Product p) {
        return List.<String[]>of(new String[]{p.getProductId(), p.getName(), p.getCategory().name()});
    }

    @Override
    protected List<Product> fromRows(List<Row> rows) throws PersistenceException {
        List<Product> products = new ArrayList<>();
        for (Row row : rows) {
            String[] f = row.fields();
            try {
                products.add(new Product(f[0], f[1], Category.fromString(f[2])));
            } catch (InventoryException e) {
                throw error(row, e.getMessage());
            }
        }
        return products;
    }
}

class BatchCsvRepository extends CsvRepository<Batch> {

    public BatchCsvRepository(Path file) {
        super(file, "batchNumber", "productId", "receivedQuantity", "availableQuantity",
                "unitPurchaseCost", "unitSellingPrice", "receivedDate", "expiryDate");
    }

    @Override
    protected List<String[]> toRows(Batch b) {
        return List.<String[]>of(new String[]{
                b.getBatchNumber(), b.getProductId(),
                String.valueOf(b.getReceivedQuantity()), String.valueOf(b.getAvailableQuantity()),
                MoneyUtil.format(b.getUnitPurchaseCost()), MoneyUtil.format(b.getUnitSellingPrice()),
                b.getReceivedDate().toString(), b.getExpiryDate().toString()});
    }

    @Override
    protected List<Batch> fromRows(List<Row> rows) throws PersistenceException {
        List<Batch> batches = new ArrayList<>();
        for (Row row : rows) {
            String[] f = row.fields();
            int received = intField(row, 2, "receivedQuantity");
            int available = intField(row, 3, "availableQuantity");
            try {
                batches.add(new Batch(f[0], f[1], received, available,
                        moneyField(row, 4, "unitPurchaseCost"), moneyField(row, 5, "unitSellingPrice"),
                        dateField(row, 6, "receivedDate"), dateField(row, 7, "expiryDate")));
            } catch (InventoryException e) {
                throw error(row, e.getMessage());
            }
        }
        return batches;
    }
}

class SaleCsvRepository extends CsvRepository<SaleTransaction> {

    public SaleCsvRepository(Path file) {
        super(file, "saleId", "date", "productId", "productName", "batchNumber", "quantity", "unitPrice");
    }

    @Override
    protected List<String[]> toRows(SaleTransaction sale) {
        List<String[]> rows = new ArrayList<>();
        for (SaleLine line : sale.getLines()) {
            rows.add(new String[]{sale.getTransactionId(), sale.getDate().toString(), sale.getProductId(),
                    sale.getProductName(), line.getBatchNumber(), String.valueOf(line.getQuantity()),
                    MoneyUtil.format(line.getUnitPrice())});
        }
        return rows;
    }

    @Override
    protected List<SaleTransaction> fromRows(List<Row> rows) throws PersistenceException {
        Map<String, List<Row>> bySale = new LinkedHashMap<>(); // keeps file order
        for (Row row : rows) {
            bySale.computeIfAbsent(row.fields()[0].trim(), k -> new ArrayList<>()).add(row);
        }
        List<SaleTransaction> sales = new ArrayList<>();
        for (List<Row> group : bySale.values()) {
            Row first = group.get(0);
            String[] f = first.fields();
            List<SaleLine> lines = new ArrayList<>();
            for (Row row : group) {
                String[] g = row.fields();
                if (!g[1].equals(f[1]) || !g[2].equals(f[2]) || !g[3].equals(f[3])) {
                    throw error(row, "rows of sale " + f[0] + " disagree on date, product or product name.");
                }
                try {
                    lines.add(new SaleLine(g[4], intField(row, 5, "quantity"), moneyField(row, 6, "unitPrice")));
                } catch (InventoryException e) {
                    throw error(row, e.getMessage());
                }
            }
            try {
                sales.add(new SaleTransaction(f[0], dateField(first, 1, "date"), f[2], f[3], lines));
            } catch (InventoryException e) {
                throw error(first, e.getMessage());
            }
        }
        return sales;
    }
}

class RemovalCsvRepository extends CsvRepository<StockRemoval> {

    public RemovalCsvRepository(Path file) {
        super(file, "removalId", "date", "productId", "batchNumber", "quantity", "reason",
                "unitCostSnapshot", "remarks");
    }

    @Override
    protected List<String[]> toRows(StockRemoval r) {
        return List.<String[]>of(new String[]{r.getTransactionId(), r.getDate().toString(), r.getProductId(),
                r.getBatchNumber(), String.valueOf(r.getQuantity()), r.getReason().name(),
                MoneyUtil.format(r.getUnitCostSnapshot()), r.getRemarks()});
    }

    @Override
    protected List<StockRemoval> fromRows(List<Row> rows) throws PersistenceException {
        List<StockRemoval> removals = new ArrayList<>();
        for (Row row : rows) {
            String[] f = row.fields();
            RemovalReason reason;
            try {
                reason = RemovalReason.valueOf(f[5].trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw error(row, "reason must be DAMAGED or EXPIRED but was '" + f[5] + "'.");
            }
            try {
                removals.add(new StockRemoval(f[0], dateField(row, 1, "date"), f[2], f[3],
                        intField(row, 4, "quantity"), reason, moneyField(row, 6, "unitCostSnapshot"), f[7]));
            } catch (InventoryException e) {
                throw error(row, e.getMessage());
            }
        }
        return removals;
    }
}

class ConfigRepository {

    private static final String KEY = "near.expiry.days";

    private final Path file;

    public ConfigRepository(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    public OptionalInt loadNearExpiryDays() throws PersistenceException {
        if (!Files.exists(file)) {
            return OptionalInt.empty();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new PersistenceException("Could not read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        String value = props.getProperty(KEY);
        if (value == null || value.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new PersistenceException(file.getFileName() + ": " + KEY + " must be a whole number but was '"
                    + value + "'.");
        }
    }

    public void save(int nearExpiryDays) throws PersistenceException {
        Properties props = new Properties();
        props.setProperty(KEY, String.valueOf(nearExpiryDays));
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "Fresh Grocery Expiry Watch settings");
        } catch (IOException e) {
            throw new PersistenceException("Could not write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
