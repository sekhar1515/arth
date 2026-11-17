package com.expensetracker.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Column(name = "is_demo", nullable = false)
    private boolean demo;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;
    @Column(name = "mfa_secret_enc")
    private String mfaSecretEnc;
    @Column(name = "mfa_recovery_hashes")
    private String mfaRecoveryHashes;

    protected User() {
    }

    public User(String email, String passwordHash, String displayName, boolean demo) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.demo = demo;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isDemo() { return demo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean v) { this.mfaEnabled = v; }
    public String getMfaSecretEnc() { return mfaSecretEnc; }
    public void setMfaSecretEnc(String v) { this.mfaSecretEnc = v; }
    public String getMfaRecoveryHashes() { return mfaRecoveryHashes; }
    public void setMfaRecoveryHashes(String v) { this.mfaRecoveryHashes = v; }
}
