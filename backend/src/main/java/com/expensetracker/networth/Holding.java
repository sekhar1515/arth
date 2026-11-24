package com.expensetracker.networth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Anything you own outside bank accounts: mutual funds, stocks, FDs, PPF/EPF/NPS, gold, property, vehicles. */
@Entity
@Table(name = "holdings")
public class Holding {

    public enum PriceSource { AMFI, BROKER_CSV, MANUAL, CALCULATED, EXCHANGE }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false)
    private AssetClass assetClass;
    @Column(nullable = false)
    private String name;
    private String symbol;
    private String isin;
    @Column(name = "scheme_code")
    private String schemeCode;
    private String folio;
    private String platform;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Allocation allocation;
    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", nullable = false)
    private ValuationMethod valuationMethod;
    @Column(precision = 20, scale = 6)
    private BigDecimal units;
    @Column(name = "invested_amount", precision = 19, scale = 2)
    private BigDecimal investedAmount;
    @Column(name = "current_price", precision = 19, scale = 6)
    private BigDecimal currentPrice;
    @Column(name = "manual_value", precision = 19, scale = 2)
    private BigDecimal manualValue;
    @Column(name = "interest_rate", precision = 6, scale = 3)
    private BigDecimal interestRate;
    @Column(name = "compounding_per_year")
    private Integer compoundingPerYear;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "maturity_date")
    private LocalDate maturityDate;
    @Column(name = "accrued_interest", nullable = false, precision = 19, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;
    @Column(name = "interest_accrued_to")
    private LocalDate interestAccruedTo;
    @Column(name = "price_as_of")
    private Instant priceAsOf;
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source")
    private PriceSource priceSource;
    private String notes;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldingOwner owner = HoldingOwner.SELF;
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Holding() {
    }

    public Holding(UUID userId, AssetClass assetClass, String name) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.assetClass = assetClass;
        this.name = name;
        this.allocation = assetClass.defaultAllocation();
        this.valuationMethod = assetClass.defaultValuation();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Valuation.Inputs valuationInputs() {
        return new Valuation.Inputs(valuationMethod, units, currentPrice, manualValue, investedAmount, interestRate, compoundingPerYear,
                startDate, maturityDate, accruedInterest);
    }

    public void applyPrice(BigDecimal price, Instant asOf, PriceSource source) {
        this.currentPrice = price;
        this.priceAsOf = asOf;
        this.priceSource = source;
    }

    public BigDecimal getAccruedInterest() { return accruedInterest; }
    public void setAccruedInterest(BigDecimal v) { accruedInterest = v == null ? BigDecimal.ZERO : v; }
    public LocalDate getInterestAccruedTo() { return interestAccruedTo; }
    public void setInterestAccruedTo(LocalDate v) { interestAccruedTo = v; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public AssetClass getAssetClass() { return assetClass; }
    public void setAssetClass(AssetClass v) { this.assetClass = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getIsin() { return isin; }
    public void setIsin(String v) { this.isin = v; }
    public String getSchemeCode() { return schemeCode; }
    public void setSchemeCode(String v) { this.schemeCode = v; }
    public String getFolio() { return folio; }
    public void setFolio(String v) { this.folio = v; }
    public String getPlatform() { return platform; }
    public void setPlatform(String v) { this.platform = v; }
    public Allocation getAllocation() { return allocation; }
    public void setAllocation(Allocation v) { this.allocation = v; }
    public ValuationMethod getValuationMethod() { return valuationMethod; }
    public void setValuationMethod(ValuationMethod v) { this.valuationMethod = v; }
    public BigDecimal getUnits() { return units; }
    public void setUnits(BigDecimal v) { this.units = v; }
    public BigDecimal getInvestedAmount() { return investedAmount; }
    public void setInvestedAmount(BigDecimal v) { this.investedAmount = v; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getManualValue() { return manualValue; }
    public void setManualValue(BigDecimal v) { this.manualValue = v; }
    public BigDecimal getInterestRate() { return interestRate; }
    public void setInterestRate(BigDecimal v) { this.interestRate = v; }
    public Integer getCompoundingPerYear() { return compoundingPerYear; }
    public void setCompoundingPerYear(Integer v) { this.compoundingPerYear = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate v) { this.maturityDate = v; }
    public Instant getPriceAsOf() { return priceAsOf; }
    public PriceSource getPriceSource() { return priceSource; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public HoldingOwner getOwner() { return owner; }
    public void setOwner(HoldingOwner v) { this.owner = v == null ? HoldingOwner.SELF : v; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean v) { this.archived = v; }
    public long getVersion() { return version; }
}
