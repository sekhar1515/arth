package com.expensetracker.common;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(Error error, Instant timestamp, String path) {
    public record Error(String code, String message, List<String> details) {
    }
}
