package com.expensetracker.categorisation;

import com.expensetracker.transaction.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Understands the short notes people type when paying ("sabzi", "rent nov", "petrol", "maid salary",
 * "lunch with team"). A note states intent, so it outranks merchant-based guesses: paying "Ramesh" can be rent
 * one day and groceries the next. English and common Hinglish terms; paths refer to default category names.
 */
public final class NoteDictionary {

    public enum Hint { CATEGORY, LENT_OR_REPAID, SELF_TRANSFER, CREDIT_CARD_PAYMENT }

    public record Match(Hint hint, String categoryPath, String keyword) {
    }

    private record Entry(String[] words, String path, Direction direction) {
    }

    private static Entry out(String path, String... words) {
        return new Entry(words, path, Direction.DEBIT);
    }

    private static Entry in(String path, String... words) {
        return new Entry(words, path, Direction.CREDIT);
    }

    /** Checked in order; put more specific phrases before general ones. */
    private static final List<Entry> ENTRIES = List.of(
            // money that is not income or spending
            new Entry(new String[]{"credit card bill", "cc bill", "card bill", "card payment"}, "#CARD", null),
            new Entry(new String[]{"self transfer", "own account", "to self", "self"}, "#SELF", null),
            new Entry(new String[]{"lent", "lend", "loan to", "udhaar", "udhar", "borrowed", "borrow", "repay", "repaid", "repayment",
                    "returned", "return money", "paid back", "pay back", "payback", "settle", "settlement", "splitwise"}, "#LENT", null),
            // household
            out("Rent", "rent", "house rent", "room rent", "pg rent", "kiraya", "kiraaya", "pg", "hostel"),
            out("Household Help", "maid", "bai", "cook", "driver salary", "driver", "househelp", "house help", "servant", "watchman", "security guard", "cleaning"),
            out("Groceries", "grocery", "groceries", "kirana", "ration", "sabzi", "sabji", "vegetables", "veggies", "veg", "fruits", "fruit",
                    "milk", "doodh", "dudh", "eggs", "anda", "atta", "rice", "chawal", "dal", "provisions", "supermarket", "dmart"),
            out("Bills & Utilities > Electricity", "electricity", "electric bill", "current bill", "light bill", "bijli", "eb bill", "power bill"),
            out("Bills & Utilities > Water", "water bill", "water", "pani", "water can", "bisleri"),
            out("Bills & Utilities > Internet", "wifi", "wi-fi", "internet", "broadband", "fiber", "fibre"),
            out("Bills & Utilities > Mobile Recharge", "recharge", "mobile bill", "phone bill", "postpaid", "prepaid", "dth"),
            out("Bills & Utilities > Gas", "gas cylinder", "cylinder", "lpg", "gas bill", "piped gas"),
            out("Bills & Utilities", "maintenance", "society", "society maintenance", "bill"),
            // food
            out("Food & Dining > Cafes", "chai", "tea", "coffee", "cafe", "snacks", "samosa"),
            out("Food & Dining > Food Delivery", "swiggy", "zomato", "food delivery", "ordered food"),
            out("Food & Dining > Restaurants", "lunch", "dinner", "breakfast", "restaurant", "hotel food", "treat", "party", "biryani", "dhaba", "food", "khana"),
            // transport & fuel
            out("Fuel", "petrol", "diesel", "fuel", "cng"),
            out("Transportation > Parking & Tolls", "parking", "toll", "fastag"),
            out("Transportation > Public Transport", "metro", "bus", "local train", "train pass", "bus pass"),
            out("Transportation > Cab & Auto", "auto", "rickshaw", "cab", "taxi", "uber", "ola", "rapido", "bike taxi"),
            out("Transportation", "travel allowance", "commute", "car service", "bike service", "service center", "puncture", "car wash"),
            // health & care
            out("Medical > Pharmacy", "medicine", "medicines", "dawai", "dawa", "pharmacy", "chemist", "tablets"),
            out("Medical > Doctor & Hospital", "doctor", "hospital", "clinic", "consultation", "checkup", "check up", "lab test", "blood test", "lab", "scan", "dentist"),
            out("Personal Care", "haircut", "salon", "parlour", "parlor", "barber", "spa", "grooming"),
            out("Hobbies", "gym", "fitness", "yoga", "sports", "cricket"),
            // money
            out("EMI & Loans", "emi", "loan emi", "home loan", "car loan", "personal loan"),
            out("Insurance", "insurance", "premium", "lic", "policy", "term plan", "health insurance"),
            out("Investments > SIP & Mutual Funds", "sip", "mutual fund", "mf"),
            out("Investments > Stocks", "stocks", "shares", "zerodha", "demat"),
            out("Investments", "investment", "invest", "ppf", "nps", "fd", "fixed deposit", "rd", "gold", "sgb"),
            out("Tax Payments", "tax", "income tax", "advance tax", "gst payment", "property tax"),
            out("Bank Charges", "bank charges", "charges", "penalty", "late fee", "fine"),
            // life
            out("Education", "fees", "fee", "tuition", "school", "college", "course", "books", "exam", "coaching", "stationery"),
            out("Subscriptions", "subscription", "netflix", "spotify", "prime", "hotstar", "youtube premium"),
            out("Entertainment > Movies & Events", "movie", "movies", "cinema", "concert", "event", "tickets"),
            out("Travel", "trip", "vacation", "holiday", "flight", "hotel", "train ticket", "irctc", "tour", "goa"),
            out("Shopping > Clothing", "clothes", "clothing", "shirt", "jeans", "kurta", "saree", "shoes", "dress"),
            out("Shopping > Electronics", "phone", "laptop", "headphones", "electronics", "charger"),
            out("Shopping", "shopping", "amazon", "flipkart", "myntra", "furniture", "home decor", "utensils"),
            out("Gifts", "gift", "gifts", "birthday", "wedding gift", "shagun", "anniversary"),
            out("Donations", "donation", "donate", "temple", "mandir", "church", "masjid", "gurudwara", "charity", "dakshina", "offering"),
            out("Family Expenses", "mom", "mother", "mummy", "maa", "dad", "father", "papa", "parents", "family", "home expenses", "pocket money", "wife", "husband", "kids", "son", "daughter", "sister", "brother",
                    "amma", "appa", "nanna", "akka", "didi", "bhaiya", "ammi", "abbu"),
            // income
            in("Salary", "salary", "sal", "stipend", "payroll"),
            in("Freelancing", "freelance", "client payment", "invoice", "consulting", "project payment"),
            in("Rental Income", "rent received", "rent", "tenant"),
            in("Interest", "interest"),
            in("Dividends", "dividend"),
            in("Cashback & Rewards", "cashback", "reward", "rewards", "scratch card"),
            in("Business", "business", "sales", "shop income")
    );

    private NoteDictionary() {
    }

    /** Lower-cased, punctuation-free, single-spaced note; the key used for rules and learning. */
    public static String normalise(String note) {
        if (note == null) return null;
        String n = note.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u0900-\\u097F ]", " ").replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? null : (n.length() > 120 ? n.substring(0, 120) : n);
    }

    private static final java.util.Set<String> NOISE = java.util.Set.of(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
            "payment", "paid", "pay", "for", "to", "the", "of", "a", "an", "and", "my", "at", "in", "on", "this", "that",
            "month", "monthly", "week", "weekly", "amount", "amt", "txn", "transaction", "sent", "via", "rs", "inr");

    /**
     * The part of a note that says what it was for: lower-cased, with months, years, amounts and filler words removed,
     * so "Chitti payment Nov 2026" and "chitti dec" both learn as "chitti". Falls back to the normalised note.
     */
    public static String coreKey(String note) {
        String n = normalise(note);
        if (n == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (NOISE.contains(w) || w.matches("\\d+(st|nd|rd|th)?") || w.matches("\\d+k")) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w);
        }
        return sb.isEmpty() ? n : sb.toString();
    }

    public static Optional<Match> lookup(String note, Direction direction) {
        String n = normalise(note);
        if (n == null) return Optional.empty();
        String hay = " " + n + " ";
        for (Entry e : ENTRIES) {
            if (e.direction() != null && e.direction() != direction) continue;
            for (String w : e.words()) {
                if (hay.contains(" " + w + " ")) {
                    return Optional.of(switch (e.path()) {
                        case "#CARD" -> new Match(Hint.CREDIT_CARD_PAYMENT, null, w);
                        case "#SELF" -> new Match(Hint.SELF_TRANSFER, null, w);
                        case "#LENT" -> new Match(Hint.LENT_OR_REPAID, null, w);
                        default -> new Match(Hint.CATEGORY, e.path(), w);
                    });
                }
            }
        }
        return Optional.empty();
    }

    /** True when the note contains the rule pattern as whole words. */
    public static boolean matchesPattern(String note, String pattern) {
        String n = normalise(note);
        String p = normalise(pattern);
        if (n == null || p == null) return false;
        return (" " + n + " ").contains(" " + p + " ");
    }
}
