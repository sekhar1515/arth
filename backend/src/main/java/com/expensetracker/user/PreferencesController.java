package com.expensetracker.user;

import com.expensetracker.audit.AuditService;
import com.expensetracker.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    public record PreferencesDto(CategorisationMode categorisationMode, BigDecimal autoConfidenceThreshold,
                                 String currency, String theme, boolean externalAiConsent) {
        static PreferencesDto of(UserPreferences p) {
            return new PreferencesDto(p.getCategorisationMode(), p.getAutoConfidenceThreshold(), p.getCurrency(),
                    p.getTheme(), p.isExternalAiConsent());
        }
    }

    public record UpdatePreferencesRequest(
            CategorisationMode categorisationMode,
            @DecimalMin("0.500") @DecimalMax("0.999") BigDecimal autoConfidenceThreshold,
            @Pattern(regexp = "light|dark|system") String theme,
            Boolean externalAiConsent) {
    }

    private final UserPreferencesRepository repository;
    private final AuditService audit;

    public PreferencesController(UserPreferencesRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PreferencesDto get() {
        return PreferencesDto.of(load());
    }

    @PutMapping
    @Transactional
    public PreferencesDto update(@Valid @RequestBody UpdatePreferencesRequest req) {
        UserPreferences p = load();
        if (req.categorisationMode() != null && req.categorisationMode() != p.getCategorisationMode()) {
            audit.record(p.getUserId(), "PREFERENCE_MODE_CHANGED", "user_preferences", p.getUserId(),
                    p.getCategorisationMode() + " -> " + req.categorisationMode());
            p.setCategorisationMode(req.categorisationMode());
        }
        if (req.autoConfidenceThreshold() != null) p.setAutoConfidenceThreshold(req.autoConfidenceThreshold());
        if (req.theme() != null) p.setTheme(req.theme());
        if (req.externalAiConsent() != null && req.externalAiConsent() != p.isExternalAiConsent()) {
            audit.record(p.getUserId(), "EXTERNAL_AI_CONSENT", "user_preferences", p.getUserId(),
                    req.externalAiConsent() ? "granted" : "revoked");
            p.setExternalAiConsent(req.externalAiConsent());
        }
        return PreferencesDto.of(p);
    }

    private UserPreferences load() {
        var userId = CurrentUser.id();
        return repository.findById(userId).orElseGet(() -> repository.save(new UserPreferences(userId)));
    }
}
