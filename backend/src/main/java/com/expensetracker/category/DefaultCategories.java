package com.expensetracker.category;

import java.util.List;

/** Default category tree copied to every new user (they can rename, recolour, archive, merge). */
public final class DefaultCategories {

    /** {@code investment}: money moved into savings/investments; counted as saved, not spent, on the dashboard. */
    public record Def(String name, String kind, String group, String icon, String color, List<String> children, boolean investment) {
        public Def(String name, String kind, String group, String icon, String color, List<String> children) {
            this(name, kind, group, icon, color, children, false);
        }
    }

    public static final String OTHER_EXPENSE = "Other";
    public static final String OTHER_INCOME = "Other Income";

    public static final List<Def> ALL = List.of(
            // Essential
            new Def("Rent", "EXPENSE", "Essential", "home", "#6366F1", List.of()),
            new Def("Groceries", "EXPENSE", "Essential", "shopping-basket", "#22C55E", List.of()),
            new Def("Bills & Utilities", "EXPENSE", "Essential", "zap", "#F59E0B",
                    List.of("Electricity", "Water", "Internet", "Mobile Recharge", "Gas")),
            new Def("Transportation", "EXPENSE", "Essential", "car", "#0EA5E9",
                    List.of("Cab & Auto", "Public Transport", "Parking & Tolls")),
            new Def("Fuel", "EXPENSE", "Essential", "fuel", "#F97316", List.of()),
            new Def("Medical", "EXPENSE", "Essential", "heart-pulse", "#EF4444", List.of("Pharmacy", "Doctor & Hospital")),
            new Def("Insurance", "EXPENSE", "Essential", "shield", "#14B8A6", List.of()),
            new Def("Education", "EXPENSE", "Essential", "graduation-cap", "#8B5CF6", List.of()),
            new Def("Household Help", "EXPENSE", "Essential", "users", "#B45309", List.of()),
            // Lifestyle
            new Def("Food & Dining", "EXPENSE", "Lifestyle", "utensils", "#E11D48",
                    List.of("Restaurants", "Food Delivery", "Cafes")),
            new Def("Shopping", "EXPENSE", "Lifestyle", "shopping-bag", "#EC4899",
                    List.of("Online Shopping", "Clothing", "Electronics")),
            new Def("Entertainment", "EXPENSE", "Lifestyle", "clapperboard", "#A855F7", List.of("Movies & Events")),
            new Def("Subscriptions", "EXPENSE", "Lifestyle", "repeat", "#7C3AED", List.of()),
            new Def("Travel", "EXPENSE", "Lifestyle", "plane", "#06B6D4", List.of("Flights", "Trains", "Hotels")),
            new Def("Personal Care", "EXPENSE", "Lifestyle", "sparkles", "#F472B6", List.of()),
            new Def("Hobbies", "EXPENSE", "Lifestyle", "palette", "#84CC16", List.of()),
            // Financial
            new Def("EMI & Loans", "EXPENSE", "Financial", "landmark", "#64748B", List.of()),
            new Def("Investments", "EXPENSE", "Financial", "trending-up", "#10B981", List.of("SIP & Mutual Funds", "Stocks", "Deposits & Schemes"), true),
            new Def("Bank Charges", "EXPENSE", "Financial", "receipt", "#94A3B8", List.of()),
            new Def("Tax Payments", "EXPENSE", "Financial", "file-text", "#475569", List.of()),
            // Other
            new Def("Family Expenses", "EXPENSE", "Other", "users", "#D97706", List.of()),
            new Def("Gifts", "EXPENSE", "Other", "gift", "#DB2777", List.of()),
            new Def("Donations", "EXPENSE", "Other", "hand-heart", "#BE185D", List.of()),
            new Def(OTHER_EXPENSE, "EXPENSE", "Other", "circle-help", "#9CA3AF", List.of()),
            // Income
            new Def("Salary", "INCOME", "Income", "briefcase", "#16A34A", List.of()),
            new Def("Freelancing", "INCOME", "Income", "laptop", "#059669", List.of()),
            new Def("Business", "INCOME", "Income", "store", "#0D9488", List.of()),
            new Def("Rental Income", "INCOME", "Income", "building", "#0891B2", List.of()),
            new Def("Interest", "INCOME", "Income", "percent", "#2563EB", List.of()),
            new Def("Dividends", "INCOME", "Income", "coins", "#4F46E5", List.of()),
            new Def("Cashback & Rewards", "INCOME", "Income", "badge-percent", "#9333EA", List.of()),
            new Def(OTHER_INCOME, "INCOME", "Income", "circle-plus", "#6B7280", List.of())
    );

    private DefaultCategories() {
    }
}
