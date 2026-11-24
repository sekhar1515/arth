package com.expensetracker.categorisation;

import com.expensetracker.transaction.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Built-in, India-specific merchant/keyword knowledge. This is the lowest-precedence suggestion source:
 * the user's own rules and history always win. Category paths use "Parent" or "Parent > Child" and refer to
 * {@link com.expensetracker.category.DefaultCategories} names.
 */
public final class KeywordDictionary {

    public enum Hint { CATEGORY, CREDIT_CARD_PAYMENT, SELF_TRANSFER, LENT_OR_REPAID }

    public record Match(Hint hint, String categoryPath, double confidence, String keyword) {
    }

    private record Entry(String keyword, String path, Direction direction, double confidence) {
    }

    private static final double STRONG = 0.80;
    private static final double MEDIUM = 0.65;

    private static final List<Entry> ENTRIES = List.of(
            e("SWIGGY INSTAMART", "Groceries"), e("INSTAMART", "Groceries"),
            e("SWIGGY", "Food & Dining > Food Delivery"), e("ZOMATO", "Food & Dining > Food Delivery"),
            e("EATSURE", "Food & Dining > Food Delivery"), e("DOMINOS", "Food & Dining > Food Delivery"),
            e("STARBUCKS", "Food & Dining > Cafes"), e("CHAAYOS", "Food & Dining > Cafes"), e("CAFE COFFEE DAY", "Food & Dining > Cafes"),
            e("MCDONALDS", "Food & Dining > Restaurants"), e("KFC", "Food & Dining > Restaurants"), e("RESTAURANT", "Food & Dining > Restaurants", MEDIUM),
            e("BLINKIT", "Groceries"), e("ZEPTO", "Groceries"), e("BIGBASKET", "Groceries"), e("DMART", "Groceries"),
            e("JIOMART", "Groceries"), e("MORE RETAIL", "Groceries"), e("RATNADEEP", "Groceries"), e("KIRANA", "Groceries", MEDIUM),
            e("AMAZON", "Shopping > Online Shopping", MEDIUM), e("FLIPKART", "Shopping > Online Shopping"),
            e("MEESHO", "Shopping > Online Shopping"), e("MYNTRA", "Shopping > Clothing"), e("AJIO", "Shopping > Clothing"),
            e("CROMA", "Shopping > Electronics"), e("RELIANCE DIGITAL", "Shopping > Electronics"),
            e("UBER", "Transportation > Cab & Auto"), e("OLA", "Transportation > Cab & Auto"), e("RAPIDO", "Transportation > Cab & Auto"),
            e("NAMMA YATRI", "Transportation > Cab & Auto"), e("METRO", "Transportation > Public Transport", MEDIUM),
            e("FASTAG", "Transportation > Parking & Tolls"), e("PARKING", "Transportation > Parking & Tolls"),
            e("INDIAN OIL", "Fuel"), e("BHARAT PETROLEUM", "Fuel"), e("HP PETROL", "Fuel"), e("IOCL", "Fuel"),
            e("BPCL", "Fuel"), e("HPCL", "Fuel"), e("SHELL", "Fuel", MEDIUM), e("PETROL", "Fuel"), e("FUEL", "Fuel", MEDIUM),
            e("ELECTRICITY", "Bills & Utilities > Electricity"), e("BESCOM", "Bills & Utilities > Electricity"),
            e("TSSPDCL", "Bills & Utilities > Electricity"), e("TANGEDCO", "Bills & Utilities > Electricity"),
            e("MSEDCL", "Bills & Utilities > Electricity"), e("TATA POWER", "Bills & Utilities > Electricity"),
            e("ADANI ELECTRICITY", "Bills & Utilities > Electricity"), e("WATER", "Bills & Utilities > Water", MEDIUM),
            e("ACT FIBERNET", "Bills & Utilities > Internet"), e("BROADBAND", "Bills & Utilities > Internet"),
            e("JIOFIBER", "Bills & Utilities > Internet"), e("AIRTEL XSTREAM", "Bills & Utilities > Internet"),
            e("JIO", "Bills & Utilities > Mobile Recharge", MEDIUM), e("AIRTEL", "Bills & Utilities > Mobile Recharge", MEDIUM),
            e("VODAFONE", "Bills & Utilities > Mobile Recharge"), e("RECHARGE", "Bills & Utilities > Mobile Recharge", MEDIUM),
            e("INDANE", "Bills & Utilities > Gas"), e("BHARATGAS", "Bills & Utilities > Gas"), e("MAHANAGAR GAS", "Bills & Utilities > Gas"),
            e("NETFLIX", "Subscriptions"), e("SPOTIFY", "Subscriptions"), e("JIOHOTSTAR", "Subscriptions"),
            e("YOUTUBE PREMIUM", "Subscriptions"), e("PRIME VIDEO", "Subscriptions"), e("APPLE COM BILL", "Subscriptions"),
            e("GOOGLE PLAY", "Subscriptions", MEDIUM), e("SONYLIV", "Subscriptions"), e("ZEE5", "Subscriptions"),
            e("BOOKMYSHOW", "Entertainment > Movies & Events"), e("PVR", "Entertainment > Movies & Events"),
            e("INOX", "Entertainment > Movies & Events"), e("DISTRICT", "Entertainment > Movies & Events", MEDIUM),
            e("IRCTC", "Travel > Trains"), e("INDIGO", "Travel > Flights"), e("AIR INDIA", "Travel > Flights"),
            e("AKASA", "Travel > Flights"), e("MAKEMYTRIP", "Travel"), e("GOIBIBO", "Travel"), e("CLEARTRIP", "Travel"),
            e("OYO", "Travel > Hotels"), e("AIRBNB", "Travel > Hotels"), e("REDBUS", "Travel"),
            e("APOLLO PHARMACY", "Medical > Pharmacy"), e("PHARMEASY", "Medical > Pharmacy"), e("NETMEDS", "Medical > Pharmacy"),
            e("TATA 1MG", "Medical > Pharmacy"), e("MEDPLUS", "Medical > Pharmacy"), e("PHARMACY", "Medical > Pharmacy"),
            e("HOSPITAL", "Medical > Doctor & Hospital"), e("CLINIC", "Medical > Doctor & Hospital"), e("DIAGNOSTIC", "Medical > Doctor & Hospital"),
            e("LIC", "Insurance", MEDIUM), e("INSURANCE", "Insurance"), e("POLICYBAZAAR", "Insurance"),
            e("SCHOOL", "Education", MEDIUM), e("COLLEGE", "Education", MEDIUM), e("UDEMY", "Education"), e("COURSERA", "Education"),
            e("URBAN COMPANY", "Personal Care"), e("SALON", "Personal Care"), e("NYKAA", "Personal Care"),
            e("EMI", "EMI & Loans", MEDIUM), e("LOAN", "EMI & Loans", MEDIUM), e("BAJAJ FINANCE", "EMI & Loans"),
            e("ZERODHA", "Investments > Stocks"), e("GROWW", "Investments > SIP & Mutual Funds"),
            e("SIP", "Investments > SIP & Mutual Funds", MEDIUM), e("MUTUAL FUND", "Investments > SIP & Mutual Funds"),
            e("BSE STAR MF", "Investments > SIP & Mutual Funds"), e("CAMS", "Investments > SIP & Mutual Funds", MEDIUM),
            e("CHARGES", "Bank Charges", MEDIUM), e("SMS CHG", "Bank Charges"), e("ANNUAL FEE", "Bank Charges"), e("GST", "Bank Charges", 0.5),
            e("INCOME TAX", "Tax Payments"), e("TIN NSDL", "Tax Payments"), e("ADVANCE TAX", "Tax Payments"),
            e("DONATION", "Donations"), e("CHARITY", "Donations"),
            e("TEMPLE", "Temple & Pooja", MEDIUM), e("MANDIR", "Temple & Pooja", MEDIUM), e("DEVASTHANAM", "Temple & Pooja"),
            e("DEVASTHANAMS", "Temple & Pooja"), e("TTD", "Temple & Pooja", MEDIUM), e("TIRUMALA", "Temple & Pooja"),
            e("SHIRDI", "Temple & Pooja", MEDIUM), e("SAI SANSTHAN", "Temple & Pooja"), e("ISKCON", "Temple & Pooja"),
            e("POOJA SAMAGRI", "Temple & Pooja"), e("PUJA SAMAGRI", "Temple & Pooja"),
            // income
            c("SALARY", "Salary", 0.90), c("SAL CREDIT", "Salary", 0.90), c("PAYROLL", "Salary", 0.85),
            c("INTEREST", "Interest", 0.85), c("INT PD", "Interest", 0.85), c("INT CREDIT", "Interest", 0.85),
            c("DIVIDEND", "Dividends", 0.85), c("CASHBACK", "Cashback & Rewards", 0.85), c("REWARD", "Cashback & Rewards", 0.7),
            c("RENT RECEIVED", "Rental Income", 0.8)
    );

    private static final List<String> CARD_PAYMENT = List.of(
            "CREDIT CARD", "CC PAYMENT", "CARD PAYMENT", "CRED CLUB", "BILLDESK CC", "AUTOPAY CC", "CC BILL");
    private static final List<String> SELF_TRANSFER = List.of("SELF TRANSFER", "OWN ACCOUNT", "TO SELF", "SELF A/C", " SELF ");

    private KeywordDictionary() {
    }

    private static Entry e(String k, String path) {
        return new Entry(k, path, Direction.DEBIT, STRONG);
    }

    private static Entry e(String k, String path, double conf) {
        return new Entry(k, path, Direction.DEBIT, conf);
    }

    private static Entry c(String k, String path, double conf) {
        return new Entry(k, path, Direction.CREDIT, conf);
    }

    public static Optional<Match> lookup(String merchantName, String description, Direction direction) {
        String haystack = " " + ((merchantName == null ? "" : merchantName) + " " + (description == null ? "" : description))
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        if (direction == Direction.DEBIT) {
            for (String k : CARD_PAYMENT) {
                if (haystack.contains(" " + k + " ")) return Optional.of(new Match(Hint.CREDIT_CARD_PAYMENT, null, 0.85, k));
            }
        }
        for (String k : SELF_TRANSFER) {
            String needle = k.startsWith(" ") ? k : " " + k + " ";
            if (haystack.contains(needle)) return Optional.of(new Match(Hint.SELF_TRANSFER, null, 0.75, k.trim()));
        }
        for (Entry entry : ENTRIES) {
            if (entry.direction() != direction) continue;
            if (haystack.contains(" " + entry.keyword() + " ")) {
                return Optional.of(new Match(Hint.CATEGORY, entry.path(), entry.confidence(), entry.keyword()));
            }
        }
        return Optional.empty();
    }
}
