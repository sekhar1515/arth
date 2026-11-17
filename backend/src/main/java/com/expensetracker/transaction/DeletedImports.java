package com.expensetracker.transaction;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tombstones for deleted imported transactions. Deleting an imported row records its source id here so that
 * importing the same statement again does not bring it back. Undoing a whole import does not leave tombstones.
 */
@Repository
public class DeletedImports {

    private final NamedParameterJdbcTemplate jdbc;

    public DeletedImports(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(UUID userId, SourceType type, String externalId) {
        jdbc.update("INSERT INTO deleted_imports (user_id, source_type, external_id) VALUES (:u, :t, :e) ON CONFLICT DO NOTHING",
                params(userId, type, externalId));
    }

    public boolean wasDeleted(UUID userId, SourceType type, String externalId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM deleted_imports WHERE user_id = :u AND source_type = :t AND external_id = :e",
                params(userId, type, externalId), Integer.class);
        return n != null && n > 0;
    }

    /** Lets a user re-import rows they deleted (e.g. after deleting by mistake). */
    public int forgetAll(UUID userId) {
        return jdbc.update("DELETE FROM deleted_imports WHERE user_id = :u", new MapSqlParameterSource("u", userId));
    }

    public int count(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM deleted_imports WHERE user_id = :u", new MapSqlParameterSource("u", userId), Integer.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource params(UUID userId, SourceType type, String externalId) {
        return new MapSqlParameterSource("u", userId).addValue("t", type.name()).addValue("e", externalId);
    }
}
