package com.expensetracker.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InrFormatTest {
    @Test
    void indianDigitGrouping() {
        assertEquals("₹0", InrFormat.format(BigDecimal.ZERO));
        assertEquals("₹999", InrFormat.format(new BigDecimal("999")));
        assertEquals("₹1,000", InrFormat.format(new BigDecimal("1000")));
        assertEquals("₹24,500", InrFormat.format(new BigDecimal("24500.00")));
        assertEquals("₹1,02,346", InrFormat.format(new BigDecimal("102345.50")));
        assertEquals("₹12,34,56,789", InrFormat.format(new BigDecimal("123456789")));
        assertEquals("-₹4,000", InrFormat.format(new BigDecimal("-4000")));
    }
}
