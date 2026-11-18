-- Forgot password: single-use, short-lived reset links. Only a SHA-256 of each token is stored.
CREATE TABLE password_reset_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    requested_ip  VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_reset_tokens_user ON password_reset_tokens (user_id);

-- Sessions issued before this moment are no longer accepted (set when a password is reset or changed).
ALTER TABLE users ADD COLUMN sessions_valid_after TIMESTAMPTZ;
