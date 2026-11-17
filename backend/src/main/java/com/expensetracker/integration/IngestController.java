package com.expensetracker.integration;

import com.expensetracker.auth.LoginRateLimiter;
import com.expensetracker.common.ApiException;
import com.expensetracker.integration.provider.Providers;
import com.expensetracker.transaction.SourceType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Webhook used by the SMS forwarder / Gmail Apps Script. Authenticated by a per-integration token (header
 * X-Ingest-Token), never by the user's session or password. Accepts alert text only.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    public record IngestRequest(String text, Instant receivedAt, String sender) {
    }

    private final IntegrationService integrations;
    private final AlertIngestService alerts;
    private final LoginRateLimiter limiter;

    public IngestController(IntegrationService integrations, AlertIngestService alerts, LoginRateLimiter limiter) {
        this.integrations = integrations;
        this.alerts = alerts;
        this.limiter = limiter;
    }

    @PostMapping("/{channel}")
    public AlertIngestService.AlertResult ingest(@PathVariable String channel,
                                                 @RequestHeader(value = "X-Ingest-Token", required = false) String token,
                                                 @RequestBody IngestRequest req) {
        String c = channel.toLowerCase(Locale.ROOT);
        String expectedProvider = switch (c) {
            case "sms" -> Providers.SMS_FORWARDER;
            case "email" -> Providers.EMAIL_ALERTS;
            default -> throw ApiException.notFound("Ingest channel");
        };
        AccountIntegration integration = integrations.byToken(token)
                .filter(i -> i.getProvider().equals(expectedProvider))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_INGEST_TOKEN", "Missing or invalid ingest token"));
        if (!limiter.tryAcquire("ingest:" + integration.getId(), 120, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many alerts per minute");
        }
        if (req == null || req.text() == null || req.text().isBlank()) throw ApiException.badRequest("text is required");
        if (req.text().length() > 5000) throw ApiException.badRequest("text is too long (max 5000 characters)");
        return alerts.ingest(integration, c.equals("sms") ? SourceType.SMS : SourceType.EMAIL, req.text(), req.receivedAt());
    }
}
