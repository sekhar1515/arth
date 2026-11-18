package com.expensetracker.privacy;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.transaction.DeletedImports;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Your data, your call: a full JSON export, and permanent account deletion. */
@Service
public class PrivacyService {

    /** Tables exported, each filtered to the user. Password hash and two-factor secrets are never exported. */
    private static final Map<String, String> EXPORT = new LinkedHashMap<>();

    static {
        EXPORT.put("preferences", "SELECT * FROM user_preferences WHERE user_id = :u");
        EXPORT.put("accounts", "SELECT * FROM financial_accounts WHERE user_id = :u ORDER BY created_at");
        EXPORT.put("categories", "SELECT * FROM categories WHERE user_id = :u ORDER BY sort_order, name");
        EXPORT.put("merchants", "SELECT * FROM merchants WHERE user_id = :u ORDER BY name");
        EXPORT.put("rules", "SELECT * FROM merchant_rules WHERE user_id = :u");
        EXPORT.put("transactions", "SELECT * FROM transactions WHERE user_id = :u ORDER BY txn_at");
        EXPORT.put("transactionSplits", "SELECT s.* FROM transaction_splits s JOIN transactions t ON t.id = s.transaction_id WHERE t.user_id = :u");
        EXPORT.put("transactionSources", "SELECT id, transaction_id, source_type, external_id, imported_at, sync_id FROM transaction_sources WHERE user_id = :u");
        EXPORT.put("tags", "SELECT * FROM tags WHERE user_id = :u");
        EXPORT.put("transactionTags", "SELECT tt.* FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id WHERE g.user_id = :u");
        EXPORT.put("categorisationHistory", "SELECT h.* FROM categorisation_history h JOIN transactions t ON t.id = h.transaction_id WHERE t.user_id = :u");
        EXPORT.put("holdings", "SELECT * FROM holdings WHERE user_id = :u");
        EXPORT.put("liabilities", "SELECT * FROM liabilities WHERE user_id = :u");
        EXPORT.put("netWorthSnapshots", "SELECT * FROM net_worth_snapshots WHERE user_id = :u ORDER BY snapshot_date");
        EXPORT.put("budgets", "SELECT * FROM budgets WHERE user_id = :u");
        EXPORT.put("recurringPayments", "SELECT * FROM recurring_payments WHERE user_id = :u");
        EXPORT.put("creditCardDetails", "SELECT * FROM credit_card_details WHERE user_id = :u");
        EXPORT.put("investmentPlans", "SELECT * FROM investment_plans WHERE user_id = :u");
        EXPORT.put("investmentInstallments", "SELECT * FROM investment_installments WHERE user_id = :u");
        EXPORT.put("goals", "SELECT * FROM financial_goals WHERE user_id = :u");
        EXPORT.put("goalContributions", "SELECT * FROM goal_contributions WHERE user_id = :u");
        EXPORT.put("goalHoldings", "SELECT gh.* FROM goal_holdings gh JOIN financial_goals g ON g.id = gh.goal_id WHERE g.user_id = :u");
        EXPORT.put("notifications", "SELECT * FROM notifications WHERE user_id = :u ORDER BY created_at");
        EXPORT.put("notificationPreferences", "SELECT * FROM notification_preferences WHERE user_id = :u");
        EXPORT.put("integrations", "SELECT id, provider, status, default_account_id, created_at, last_sync_at FROM account_integrations WHERE user_id = :u");
        EXPORT.put("auditLog", "SELECT created_at, action, entity_type, entity_id, detail FROM audit_log WHERE user_id = :u ORDER BY created_at");
    }

    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final DeletedImports deletedImports;
    private final AuditService audit;

    public PrivacyService(UserRepository users, NamedParameterJdbcTemplate jdbc, PasswordEncoder encoder, DeletedImports deletedImports, AuditService audit) {
        this.users = users;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.deletedImports = deletedImports;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "arth-export-v1");
        out.put("exportedAt", Instant.now().toString());
        out.put("user", Map.of("id", u.getId(), "email", u.getEmail(), "displayName", u.getDisplayName(), "createdAt", u.getCreatedAt().toString()));
        MapSqlParameterSource p = new MapSqlParameterSource("u", userId);
        EXPORT.forEach((name, sql) -> out.put(name, jdbc.queryForList(sql, p)));
        audit.record(userId, "DATA_EXPORTED", "user", userId, null);
        return out;
    }

    @Transactional
    public int forgetDeletedImports(UUID userId) {
        int n = deletedImports.forgetAll(userId);
        audit.record(userId, "DELETED_IMPORTS_CLEARED", "user", userId, n + " cleared");
        return n;
    }

    @Transactional(readOnly = true)
    public int deletedImportsCount(UUID userId) {
        return deletedImports.count(userId);
    }

    /** Permanently deletes the user and everything they own. Requires the password and the word DELETE. */
    @Transactional
    public void deleteAccount(UUID userId, String password, String confirmation) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (u.isDemo()) throw ApiException.badRequest("The demo account can't be deleted");
        if (!"DELETE".equals(confirmation)) throw ApiException.badRequest("Type DELETE to confirm");
        if (password == null || !encoder.matches(password, u.getPasswordHash())) throw ApiException.badRequest("Password is incorrect");
        MapSqlParameterSource p = new MapSqlParameterSource("u", userId);
        // transactions reference accounts with ON DELETE RESTRICT, so they go first; everything else cascades from users
        jdbc.update("DELETE FROM goal_contributions WHERE user_id = :u", p);
        jdbc.update("DELETE FROM transactions WHERE user_id = :u", p);
        jdbc.update("DELETE FROM audit_log WHERE user_id = :u", p);
        jdbc.update("DELETE FROM users WHERE id = :u", p);
    }
}
