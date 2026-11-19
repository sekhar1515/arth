package com.expensetracker.networth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * XIRR: the yearly return that makes all dated cash flows add up to zero, the same measure fund houses and brokers
 * show. Money in is negative, today's value positive. Newton's method, with bisection as a fallback.
 */
public final class Xirr {

    public record Flow(LocalDate date, BigDecimal amount) {
    }

    /** Below this span an annual figure is misleading (a 2% gain in a week "is" 180% a year). */
    static final long MIN_DAYS = 30;

    private Xirr() {
    }

    /** XIRR as a percentage (e.g. 12.34), or null when it can't be worked out meaningfully. */
    public static BigDecimal percent(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return null;
        boolean in = flows.stream().anyMatch(f -> f.amount().signum() < 0);
        boolean out = flows.stream().anyMatch(f -> f.amount().signum() > 0);
        if (!in || !out) return null;
        LocalDate first = flows.stream().map(Flow::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = flows.stream().map(Flow::date).max(Comparator.naturalOrder()).orElseThrow();
        if (ChronoUnit.DAYS.between(first, last) < MIN_DAYS) return null;
        int n = flows.size();
        double[] t = new double[n];
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = ChronoUnit.DAYS.between(first, flows.get(i).date()) / 365.0;
            a[i] = flows.get(i).amount().doubleValue();
        }
        Double r = newton(t, a);
        if (r == null) r = bisect(t, a);
        if (r == null || r.isNaN() || r.isInfinite()) return null;
        return BigDecimal.valueOf(r * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static double npv(double r, double[] t, double[] a) {
        double s = 0;
        for (int i = 0; i < t.length; i++) s += a[i] / Math.pow(1 + r, t[i]);
        return s;
    }

    private static double dnpv(double r, double[] t, double[] a) {
        double s = 0;
        for (int i = 0; i < t.length; i++) s -= t[i] * a[i] / Math.pow(1 + r, t[i] + 1);
        return s;
    }

    private static Double newton(double[] t, double[] a) {
        double r = 0.1;
        for (int i = 0; i < 100; i++) {
            double f = npv(r, t, a);
            double d = dnpv(r, t, a);
            if (d == 0 || Double.isNaN(d)) return null;
            double next = r - f / d;
            if (next <= -0.9999) next = (r - 0.9999) / 2;
            if (Math.abs(next - r) < 1e-9) return next;
            r = next;
        }
        return null;
    }

    private static Double bisect(double[] t, double[] a) {
        double lo = -0.9999, hi = 10;
        double flo = npv(lo, t, a), fhi = npv(hi, t, a);
        if (Math.signum(flo) == Math.signum(fhi)) return null;
        for (int i = 0; i < 300; i++) {
            double mid = (lo + hi) / 2, fm = npv(mid, t, a);
            if (Math.abs(fm) < 1e-7) return mid;
            if (Math.signum(fm) == Math.signum(flo)) {
                lo = mid;
                flo = fm;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }
}
