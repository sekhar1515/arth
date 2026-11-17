package com.expensetracker.analytics;

import com.expensetracker.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    /** @param month YYYY-MM; defaults to the current month */
    @GetMapping("/report")
    public AnalyticsService.Report report(@RequestParam(required = false) String month) {
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? null : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw com.expensetracker.common.ApiException.badRequest("Month must look like 2026-10");
        }
        return service.report(CurrentUser.id(), ym);
    }
}
