package com.expensetracker.merchant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns raw counterparty strings ("SWIGGY*ORDER 1234", "BUNDL TECHNOLOGIES PVT LTD", "swiggy@icici")
 * into a display name ("Swiggy") and a stable lookup key ("swiggy").
 */
public final class MerchantNormaliser {

    public record Normalised(String displayName, String key) {
    }

    /** Known legal entity / payment-handle names → brand. Keys are matched against the cleaned upper-case text. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("BUNDL TECHNOLOGIES", "Swiggy");
        ALIASES.put("SWIGGY", "Swiggy");
        ALIASES.put("ZOMATO", "Zomato");
        ALIASES.put("ETERNAL LIMITED", "Zomato");
        ALIASES.put("BLINKIT", "Blinkit");
        ALIASES.put("GROFERS", "Blinkit");
        ALIASES.put("ZEPTO", "Zepto");
        ALIASES.put("KIRANAKART", "Zepto");
        ALIASES.put("BIGBASKET", "BigBasket");
        ALIASES.put("SUPERMARKET GROCERY SUPPLIES", "BigBasket");
        ALIASES.put("INNOVATIVE RETAIL CONCEPTS", "BigBasket");
        ALIASES.put("AMAZON PAY", "Amazon");
        ALIASES.put("AMAZON", "Amazon");
        ALIASES.put("FLIPKART", "Flipkart");
        ALIASES.put("MYNTRA", "Myntra");
        ALIASES.put("UBER", "Uber");
        ALIASES.put("ANI TECHNOLOGIES", "Ola");
        ALIASES.put("OLACABS", "Ola");
        ALIASES.put("RAPIDO", "Rapido");
        ALIASES.put("ROPPEN TRANSPORTATION", "Rapido");
        ALIASES.put("NETFLIX", "Netflix");
        ALIASES.put("SPOTIFY", "Spotify");
        ALIASES.put("JIOHOTSTAR", "JioHotstar");
        ALIASES.put("HOTSTAR", "JioHotstar");
        ALIASES.put("RELIANCE JIO", "Jio");
        ALIASES.put("JIO", "Jio");
        ALIASES.put("BHARTI AIRTEL", "Airtel");
        ALIASES.put("AIRTEL", "Airtel");
        ALIASES.put("IRCTC", "IRCTC");
        ALIASES.put("MAKEMYTRIP", "MakeMyTrip");
        ALIASES.put("INDIGO", "IndiGo");
        ALIASES.put("INTERGLOBE AVIATION", "IndiGo");
        ALIASES.put("INDIAN OIL", "Indian Oil");
        ALIASES.put("IOCL", "Indian Oil");
        ALIASES.put("BHARAT PETROLEUM", "Bharat Petroleum");
        ALIASES.put("BPCL", "Bharat Petroleum");
        ALIASES.put("HINDUSTAN PETROLEUM", "HP Petrol");
        ALIASES.put("HPCL", "HP Petrol");
        ALIASES.put("APOLLO PHARMACY", "Apollo Pharmacy");
        ALIASES.put("PHARMEASY", "PharmEasy");
        ALIASES.put("BOOKMYSHOW", "BookMyShow");
        ALIASES.put("BIG TREE ENTERTAINMENT", "BookMyShow");
        ALIASES.put("DMART", "DMart");
        ALIASES.put("AVENUE SUPERMARTS", "DMart");
    }

    private static final Pattern NOISE = Pattern.compile(
            "\\b(PVT|PRIVATE|LTD|LIMITED|LLP|INDIA|INDIAN|TECHNOLOGIES|TECHNOLOGY|SERVICES|SOLUTIONS|ONLINE|INTERNET|RETAIL|PAYMENTS?|COM|WWW|IN|ORDER|MUMBAI|BANGALORE|BENGALURU|DELHI|GURGAON|HYDERABAD|CHENNAI|PUNE)\\b");

    private MerchantNormaliser() {
    }

    public static Normalised normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        int at = text.indexOf('@');
        if (at > 0) text = text.substring(0, at);
        String upper = text.toUpperCase(Locale.ROOT)
                .replaceAll("[*_.#/\\\\|]", " ")
                .replaceAll("\\b[0-9X]{4,}\\b", " ")
                .replaceAll("[^A-Z0-9&' ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (containsWord(upper, alias.getKey())) {
                String brand = alias.getValue();
                return new Normalised(brand, key(brand));
            }
        }
        String core = NOISE.matcher(upper).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (core.isEmpty()) core = upper;
        if (core.isEmpty()) return null;
        String display = titleCase(core);
        if (display.length() > 120) display = display.substring(0, 120);
        return new Normalised(display, key(display));
    }

    private static boolean containsWord(String haystack, String needle) {
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean endOk = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
            if (startOk && endOk) return true;
            idx = haystack.indexOf(needle, idx + 1);
        }
        return false;
    }

    public static String key(String name) {
        String k = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return k.length() > 120 ? k.substring(0, 120) : k;
    }

    private static String titleCase(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String word : upper.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
