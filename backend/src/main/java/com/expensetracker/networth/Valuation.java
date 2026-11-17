package com.expensetracker.networth;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure valuation maths. Money results are rounded to paise. */
public final class Valuation {

    public record Inputs(ValuationMethod method, BigDecimal units, BigDecimal price, BigDecimal manualValue, BigDecimal invested,
                         BigDecimal annualRatePercent, Integer compoundingPerYear, LocalDate startDate, LocalDate maturityDate) {
    }

    /**
     * @param value      current value
     * @param gain       value − invested (null when invested is unknown)
     * @param gainPct    gain as % of invested (null when unknown or invested is zero)
     * @param estimated  true for fixed-rate projections and when a market price is missing
     */
    public record Result(BigDecimal value, BigDecimal invested, BigDecimal gain, BigDecimal gainPct, boolean estimated, String basis) {
    }

    private Valuation() {
    }

    public static Result value(Inputs in, LocalDate today) {
        BigDecimal value;
        boolean estimated = false;
        String basis;
        switch (in.method()) {
            case MARKET_PRICE -> {
                if (in.units() != null && in.price() != null) {
                    value = in.units().multiply(in.price());
                    basis = "units × latest price";
                } else {
                    // no price yet: fall back to what you paid
                    value = in.invested() != null ? in.invested() : BigDecimal.ZERO;
                    estimated = true;
                    basis = "no price yet: showing amount invested";
                }
            }
            case MANUAL -> {
                value = in.manualValue() != null ? in.manualValue() : BigDecimal.ZERO;
                basis = "value you entered";
            }
            case FIXED_RATE -> {
                value = compound(in.invested(), in.annualRatePercent(), in.compoundingPerYear(), in.startDate(), in.maturityDate(), today);
                estimated = true;
                basis = "estimated from rate and dates";
            }
            default -> throw new IllegalArgumentException("method");
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = null;
        BigDecimal pct = null;
        if (in.invested() != null) {
            gain = value.subtract(in.invested()).setScale(2, RoundingMode.HALF_UP);
            if (in.invested().signum() > 0) pct = gain.multiply(BigDecimal.valueOf(100)).divide(in.invested(), 2, RoundingMode.HALF_UP);
        }
        return new Result(value, in.invested(), gain, pct, estimated, basis);
    }

    /** principal × (1 + r/n)^(n·t), t in years from start to min(today, maturity). Indian banks compound FDs quarterly. */
    static BigDecimal compound(BigDecimal principal, BigDecimal ratePercent, Integer perYear, LocalDate start, LocalDate maturity, LocalDate today) {
        if (principal == null) return BigDecimal.ZERO;
        if (ratePercent == null || start == null || !today.isAfter(start)) return principal;
        LocalDate end = maturity != null && maturity.isBefore(today) ? maturity : today;
        double years = ChronoUnit.DAYS.between(start, end) / 365.0;
        int n = perYear == null || perYear <= 0 ? 4 : perYear;
        double factor = Math.pow(1 + ratePercent.doubleValue() / 100.0 / n, n * years);
        return principal.multiply(new BigDecimal(factor, MathContext.DECIMAL64));
    }
}
