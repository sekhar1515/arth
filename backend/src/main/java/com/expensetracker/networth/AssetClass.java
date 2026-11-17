package com.expensetracker.networth;

public enum AssetClass {
    MUTUAL_FUND("Mutual fund", Allocation.EQUITY, ValuationMethod.MARKET_PRICE),
    STOCK("Stock", Allocation.EQUITY, ValuationMethod.MARKET_PRICE),
    ETF("ETF", Allocation.EQUITY, ValuationMethod.MARKET_PRICE),
    FIXED_DEPOSIT("Fixed deposit", Allocation.DEBT, ValuationMethod.FIXED_RATE),
    RECURRING_DEPOSIT("Recurring deposit", Allocation.DEBT, ValuationMethod.MANUAL),
    PPF("PPF", Allocation.DEBT, ValuationMethod.MANUAL),
    EPF("EPF / PF", Allocation.DEBT, ValuationMethod.MANUAL),
    NPS("NPS", Allocation.HYBRID, ValuationMethod.MANUAL),
    BOND("Bond", Allocation.DEBT, ValuationMethod.MARKET_PRICE),
    GOLD("Gold", Allocation.GOLD, ValuationMethod.MARKET_PRICE),
    SGB("Sovereign Gold Bond", Allocation.GOLD, ValuationMethod.MARKET_PRICE),
    REAL_ESTATE("Property", Allocation.REAL_ESTATE, ValuationMethod.MANUAL),
    CRYPTO("Crypto", Allocation.OTHER, ValuationMethod.MARKET_PRICE),
    VEHICLE("Vehicle", Allocation.OTHER, ValuationMethod.MANUAL),
    OTHER("Other asset", Allocation.OTHER, ValuationMethod.MANUAL);

    private final String label;
    private final Allocation defaultAllocation;
    private final ValuationMethod defaultValuation;

    AssetClass(String label, Allocation defaultAllocation, ValuationMethod defaultValuation) {
        this.label = label;
        this.defaultAllocation = defaultAllocation;
        this.defaultValuation = defaultValuation;
    }

    public String label() { return label; }
    public Allocation defaultAllocation() { return defaultAllocation; }
    public ValuationMethod defaultValuation() { return defaultValuation; }

    /** Guess a mutual fund's bucket from its AMFI category ("Debt Scheme - Liquid Fund", "Hybrid Scheme - ..."). */
    public static Allocation allocationForFundCategory(String amfiCategory) {
        if (amfiCategory == null) return Allocation.EQUITY;
        String c = amfiCategory.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("debt") || c.contains("liquid") || c.contains("money market") || c.contains("gilt") || c.contains("overnight")) return Allocation.DEBT;
        if (c.contains("hybrid") || c.contains("balanced") || c.contains("arbitrage")) return Allocation.HYBRID;
        if (c.contains("gold")) return Allocation.GOLD;
        return Allocation.EQUITY;
    }
}
