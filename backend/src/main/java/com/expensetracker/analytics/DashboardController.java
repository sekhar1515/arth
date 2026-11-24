package com.expensetracker.analytics;

import com.expensetracker.auth.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;
    private final com.expensetracker.networth.InvestmentPlanService plans;

    public DashboardController(DashboardService service, com.expensetracker.networth.InvestmentPlanService plans) {
        this.service = service;
        this.plans = plans;
    }

    @GetMapping("/summary")
    public DashboardService.Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        java.util.UUID userId = CurrentUser.id();
        plans.runDue(userId); // SIP/contribution debits due by today, so balances are current
        return service.summary(userId, from, to);
    }

    @GetMapping("/monthly")
    public List<DashboardService.MonthPoint> monthly(@RequestParam(defaultValue = "6") int months) {
        return service.monthly(CurrentUser.id(), months);
    }

    @GetMapping("/daily")
    public List<DashboardService.DayPoint> daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.daily(CurrentUser.id(), from, to);
    }
}
