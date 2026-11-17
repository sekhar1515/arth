package com.expensetracker.networth;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import com.expensetracker.common.TimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class LiabilityService {

    public record LiabilityRequest(Liability.Type type, String name, String lender, BigDecimal principal, BigDecimal outstanding,
                                   BigDecimal interestRate, BigDecimal emi, LocalDate startDate, LocalDate endDate, LocalDate asOf,
                                   String notes, Long version) {
    }

    public record LiabilityDto(UUID id, Liability.Type type, String name, String lender, BigDecimal principal, BigDecimal outstanding,
                               BigDecimal interestRate, BigDecimal emi, LocalDate startDate, LocalDate endDate, LocalDate asOf,
                               BigDecimal repaidPct, String notes, boolean archived, long version) {
        static LiabilityDto of(Liability l) {
            BigDecimal repaid = l.getPrincipal() != null && l.getPrincipal().signum() > 0
                    ? BigDecimal.ONE.subtract(l.getOutstanding().divide(l.getPrincipal(), 6, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                    : null;
            return new LiabilityDto(l.getId(), l.getType(), l.getName(), l.getLender(), l.getPrincipal(), l.getOutstanding(), l.getInterestRate(),
                    l.getEmi(), l.getStartDate(), l.getEndDate(), l.getAsOf(), repaid, l.getNotes(), l.isArchived(), l.getVersion());
        }
    }

    private final LiabilityRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public LiabilityService(LiabilityRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LiabilityDto> list(UUID userId) {
        return repository.findByUserIdOrderByArchivedAscOutstandingDesc(userId).stream().map(LiabilityDto::of).toList();
    }

    @Transactional
    public LiabilityDto create(UUID userId, LiabilityRequest r) {
        if (r.type() == null) throw ApiException.badRequest("Choose the type of loan");
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name is required");
        if (r.outstanding() == null) throw ApiException.badRequest("Enter how much is still owed");
        Liability l = new Liability(userId, r.type(), r.name().trim(), money(r.outstanding(), "Outstanding"), r.asOf() != null ? r.asOf() : TimeConfig.today(clock));
        apply(l, r);
        l = repository.save(l);
        audit.record(userId, "LIABILITY_CREATED", "liability", l.getId(), l.getType().name());
        return LiabilityDto.of(l);
    }

    @Transactional
    public LiabilityDto update(UUID userId, UUID id, LiabilityRequest r) {
        Liability l = require(userId, id);
        if (r.version() != null && r.version() != l.getVersion()) throw ApiException.conflict("This loan changed elsewhere. Reload and try again.");
        if (r.type() != null) l.setType(r.type());
        if (r.name() != null && !r.name().isBlank()) l.setName(r.name().trim());
        if (r.outstanding() != null) {
            l.setOutstanding(money(r.outstanding(), "Outstanding"));
            l.setAsOf(r.asOf() != null ? r.asOf() : TimeConfig.today(clock));
        }
        apply(l, r);
        repository.flush();
        return LiabilityDto.of(l);
    }

    @Transactional
    public void setArchived(UUID userId, UUID id, boolean archived) {
        require(userId, id).setArchived(archived);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(require(userId, id));
        audit.record(userId, "LIABILITY_DELETED", "liability", id, null);
    }

    private static void apply(Liability l, LiabilityRequest r) {
        if (r.lender() != null) l.setLender(r.lender().isBlank() ? null : r.lender().trim());
        if (r.principal() != null) l.setPrincipal(money(r.principal(), "Loan amount"));
        if (r.interestRate() != null) l.setInterestRate(r.interestRate());
        if (r.emi() != null) l.setEmi(money(r.emi(), "EMI"));
        if (r.startDate() != null) l.setStartDate(r.startDate());
        if (r.endDate() != null) l.setEndDate(r.endDate());
        if (r.notes() != null) l.setNotes(r.notes().isBlank() ? null : r.notes().trim());
    }

    private static BigDecimal money(BigDecimal v, String what) {
        if (v.signum() < 0) throw ApiException.badRequest(what + " can't be negative");
        if (v.scale() > 2) throw ApiException.badRequest(what + " has more than 2 decimal places");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private Liability require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Loan"));
    }
}
