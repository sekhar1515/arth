package com.expensetracker.networth;

import com.expensetracker.integration.parsing.SimpleCsv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads holdings exports from Indian brokers and MF platforms (Zerodha Kite/Console, Groww, Coin, INDmoney-style).
 * Understands columns such as Instrument / Stock Name / Scheme Name, ISIN, Qty / Quantity / Units,
 * Avg. cost / Average buy price, LTP / Closing price / NAV, Cur. val / Current Value, Invested / Buy value, Folio.
 */
public final class HoldingsCsvParser {

    public record HoldingRow(int rowNumber, String name, String symbol, String isin, String folio, BigDecimal quantity,
                             BigDecimal avgCost, BigDecimal price, BigDecimal currentValue, BigDecimal invested, boolean looksLikeFund) {
    }

    public record Result(List<HoldingRow> rows, List<String> errors, boolean fundFile) {
    }

    private static final String[] NAME = {"instrument", "stockname", "schemename", "scheme", "fundname", "name", "securityname", "company", "isinname"};
    private static final String[] SYMBOL = {"symbol", "tradingsymbol", "ticker", "nsesymbol"};
    private static final String[] ISIN = {"isin", "isincode"};
    private static final String[] QTY = {"qty", "quantity", "units", "quantityavailable", "balanceunits", "currentunits", "shares"};
    private static final String[] AVG = {"avgcost", "averageprice", "averagebuyprice", "avgprice", "buyaverage", "averagecost", "avgnav"};
    private static final String[] PRICE = {"ltp", "closingprice", "currentprice", "lastprice", "nav", "currentnav", "marketprice", "previousclosingprice"};
    private static final String[] VALUE = {"curval", "currentvalue", "closingvalue", "marketvalue", "presentvalue", "value"};
    private static final String[] INVESTED = {"invested", "investedvalue", "investedamount", "buyvalue", "costvalue", "totalcost", "investment"};
    private static final String[] FOLIO = {"folio", "foliono", "folionumber"};

    private HoldingsCsvParser() {
    }

    public static Result parse(String content) {
        List<List<String>> table = SimpleCsv.parse(content);
        int header = -1;
        int[] c = null;
        for (int i = 0; i < Math.min(table.size(), 30); i++) {
            int[] m = map(table.get(i));
            if (m != null) {
                header = i;
                c = m;
                break;
            }
        }
        if (c == null) {
            throw new IllegalArgumentException("Couldn't find a holdings header. Expected columns like 'Instrument', 'Qty.', 'Avg. cost', 'LTP' "
                    + "or 'Scheme Name', 'Units', 'Current Value'.");
        }
        List<String> norm = table.get(header).stream().map(HoldingsCsvParser::norm).toList();
        boolean fundFile = norm.stream().anyMatch(h -> h.contains("scheme") || h.contains("folio") || h.equals("units") || h.contains("amc"));
        List<HoldingRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = header + 1; i < table.size(); i++) {
            List<String> r = table.get(i);
            String name = cell(r, c[0]);
            String isin = cell(r, c[2]).toUpperCase(Locale.ROOT);
            if (name.isBlank() && isin.isBlank()) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith("total")) continue;
            BigDecimal qty = num(cell(r, c[3]));
            BigDecimal avg = num(cell(r, c[4]));
            BigDecimal price = num(cell(r, c[5]));
            BigDecimal value = num(cell(r, c[6]));
            BigDecimal invested = num(cell(r, c[7]));
            if (qty == null && value == null) {
                errors.add("Row " + (i + 1) + ": no quantity or value for '" + name + "'");
                continue;
            }
            if (invested == null && qty != null && avg != null) invested = qty.multiply(avg).setScale(2, RoundingMode.HALF_UP);
            if (price == null && qty != null && qty.signum() > 0 && value != null) price = value.divide(qty, 6, RoundingMode.HALF_UP);
            if (value == null && qty != null && price != null) value = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            if (avg == null && qty != null && qty.signum() > 0 && invested != null) avg = invested.divide(qty, 6, RoundingMode.HALF_UP);
            String symbol = cell(r, c[1]);
            if (symbol.isBlank() && !fundFile && name.matches("[A-Z0-9&\\-]{1,20}")) symbol = name;
            rows.add(new HoldingRow(i + 1, name.isBlank() ? isin : name.trim(), symbol.isBlank() ? null : symbol.trim(),
                    isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]") ? isin : null, blankToNull(cell(r, c[8])), qty, avg, price, value, invested,
                    fundFile || isin.startsWith("INF")));
        }
        return new Result(rows, errors, fundFile);
    }

    private static int[] map(List<String> header) {
        List<String> n = header.stream().map(HoldingsCsvParser::norm).toList();
        int name = find(n, NAME), symbol = find(n, SYMBOL), isin = find(n, ISIN), qty = find(n, QTY), avg = find(n, AVG),
                price = find(n, PRICE), value = find(n, VALUE), invested = find(n, INVESTED), folio = find(n, FOLIO);
        if (name < 0) name = symbol >= 0 ? symbol : isin;
        if (name < 0 || (qty < 0 && value < 0)) return null;
        return new int[]{name, symbol, isin, qty, avg, price, value, invested, folio};
    }

    private static int find(List<String> n, String[] aliases) {
        for (String a : aliases) for (int i = 0; i < n.size(); i++) if (n.get(i).equals(a)) return i;
        return -1;
    }

    static String norm(String h) {
        return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", "").replaceAll("[^a-z]", "");
    }

    private static String cell(List<String> r, int i) {
        return i >= 0 && i < r.size() && r.get(i) != null ? r.get(i).trim() : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal num(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").replace("₹", "").replaceAll("(?i)inr|rs\\.?", "").replace("%", "").trim();
        if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("NA")) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
