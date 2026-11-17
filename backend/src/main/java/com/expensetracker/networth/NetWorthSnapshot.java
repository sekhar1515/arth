package com.expensetracker.networth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "net_worth_snapshots")
public class NetWorthSnapshot {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal assets;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal liabilities;
    @Column(name = "net_worth", nullable = false, precision = 19, scale = 2)
    private BigDecimal netWorth;
    private String breakdown;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NetWorthSnapshot() {
    }

    public NetWorthSnapshot(UUID userId, LocalDate date) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.snapshotDate = date;
        this.createdAt = Instant.now();
    }

    public void set(BigDecimal assets, BigDecimal liabilities, String breakdown) {
        this.assets = assets;
        this.liabilities = liabilities;
        this.netWorth = assets.subtract(liabilities);
        this.breakdown = breakdown;
    }

    public UUID getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public BigDecimal getAssets() { return assets; }
    public BigDecimal getLiabilities() { return liabilities; }
    public BigDecimal getNetWorth() { return netWorth; }
    public String getBreakdown() { return breakdown; }
}
