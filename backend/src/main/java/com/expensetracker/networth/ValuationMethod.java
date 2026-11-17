package com.expensetracker.networth;

public enum ValuationMethod {
    /** units × latest price (mutual fund NAV, share price, gold rate). */
    MARKET_PRICE,
    /** a value you enter yourself (property, EPF balance, vehicle). */
    MANUAL,
    /** principal growing at a fixed rate with compounding (FD, RD, PPF); an estimate. */
    FIXED_RATE
}
