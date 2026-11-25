import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class InputValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,30}");
    private static final int MAX_TEXT_LENGTH = 100;
    public static final int MAX_QUANTITY = 1_000_000;

    private InputValidator() {
    }

    public static String requireText(String value, String field) throws InvalidInputException {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidInputException(field + " cannot be empty.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new InvalidInputException(field + " cannot be longer than " + MAX_TEXT_LENGTH + " characters.");
        }
        if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new InvalidInputException(field + " must be a single line.");
        }
        return trimmed;
    }

    public static String optionalText(String value, String field) throws InvalidInputException {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return requireText(value, field);
    }

    public static String requireId(String value, String field) throws InvalidInputException {
        String text = requireText(value, field);
        if (!ID_PATTERN.matcher(text).matches()) {
            throw new InvalidInputException(field + " may contain only letters, digits, '-' and '_' (max 30 characters).");
        }
        return text.toUpperCase(Locale.ROOT);
    }

    public static int requirePositive(int value, String field) throws InvalidInputException {
        if (value <= 0) {
            throw new InvalidInputException(field + " must be greater than zero.");
        }
        if (value > MAX_QUANTITY) {
            throw new InvalidInputException(field + " cannot be more than " + MAX_QUANTITY + ".");
        }
        return value;
    }

    public static BigDecimal requireNonNegative(BigDecimal value, String field) throws InvalidInputException {
        if (value == null) {
            throw new InvalidInputException(field + " is required.");
        }
        if (value.signum() < 0) {
            throw new InvalidInputException(field + " cannot be negative.");
        }
        return value;
    }

    public static <T> T requireNonNull(T value, String field) throws InvalidInputException {
        if (value == null) {
            throw new InvalidInputException(field + " is required.");
        }
        return value;
    }
}

final class MoneyUtil {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");

    private MoneyUtil() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal parse(String text, String field) throws InvalidInputException {
        if (text == null || text.trim().isEmpty()) {
            throw new InvalidInputException(field + " cannot be empty.");
        }
        try {
            BigDecimal value = new BigDecimal(text.trim());
            if (value.abs().compareTo(MAX_AMOUNT) > 0) {
                throw new InvalidInputException(field + " is out of range (the limit is " + MAX_AMOUNT.toPlainString() + ").");
            }
            if (value.stripTrailingZeros().scale() > SCALE) {
                throw new InvalidInputException(field + " can have at most 2 decimal places.");
            }
            return scale(value);
        } catch (NumberFormatException e) {
            throw new InvalidInputException(field + " must be a valid number (for example 12.50).");
        } catch (ArithmeticException e) {
            throw new InvalidInputException(field + " is not a valid amount.");
        }
    }

    public static BigDecimal multiply(BigDecimal unitAmount, int quantity) {
        return scale(unitAmount.multiply(BigDecimal.valueOf(quantity)));
    }

    public static BigDecimal sum(Stream<BigDecimal> amounts) {
        return scale(amounts.reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static String format(BigDecimal value) {
        return scale(value).toPlainString();
    }
}

final class DateUtil {

    private DateUtil() {
    }

    public static LocalDate parse(String text, String field) throws InvalidInputException {
        if (text == null || text.trim().isEmpty()) {
            throw new InvalidInputException(field + " cannot be empty.");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidInputException(field + " must be a valid date in yyyy-MM-dd format (for example 2026-12-31).");
        }
    }
}

final class CsvUtil {

    private CsvUtil() {
    }

    public static String formatLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatField(fields[i]));
        }
        return sb.toString();
    }

    private static String formatField(String field) {
        String value = field == null ? "" : field;
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r") || !value.equals(value.strip());
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"'); // doubled quote = one literal quote
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                if (current.length() == 0) {
                    inQuotes = true;
                } else {
                    throw new IllegalArgumentException("unexpected quote inside a field");
                }
            } else if (ch == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quote");
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}

class TablePrinter {

    private final String[] headers;
    private final List<String[]> rows = new ArrayList<>();
    private final Set<Integer> rightAligned = new HashSet<>();

    public TablePrinter(String... headers) {
        this.headers = headers.clone();
    }

    public TablePrinter rightAlign(int... columns) {
        for (int column : columns) {
            rightAligned.add(column);
        }
        return this;
    }

    public TablePrinter addRow(String... cells) {
        if (cells.length != headers.length) {
            throw new IllegalArgumentException("Row has " + cells.length + " cells but the table has "
                    + headers.length + " columns.");
        }
        String[] row = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            row[i] = cells[i] == null ? "" : cells[i];
        }
        rows.add(row);
        return this;
    }

    public String render() {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
            for (String[] row : rows) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder sb = new StringBuilder();
        appendLine(sb, headers, widths);
        StringBuilder separator = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                separator.append("-+-");
            }
            separator.append("-".repeat(widths[i]));
        }
        sb.append(separator).append('\n');
        for (String[] row : rows) {
            appendLine(sb, row, widths);
        }
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String[] cells, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(" | ");
            }
            String pad = " ".repeat(widths[i] - cells[i].length());
            if (rightAligned.contains(i)) {
                line.append(pad).append(cells[i]);
            } else {
                line.append(cells[i]).append(pad);
            }
        }
        sb.append(line.toString().stripTrailing()).append('\n');
    }

    public static String banner(String title) {
        String bar = "=".repeat(Math.max(50, title.length() + 2));
        return bar + "\n " + title + "\n" + bar + "\n";
    }

    public static String section(String title) {
        return "\n--- " + title + " ---\n";
    }
}
