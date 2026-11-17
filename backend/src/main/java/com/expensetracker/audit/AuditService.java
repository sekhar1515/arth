package com.expensetracker.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/** Append-only audit trail for security- and money-relevant actions. Never records secrets or alert text. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID userId, String action, String entityType, UUID entityId, String detail) {
        repository.save(new AuditLog(userId, action, entityType, entityId, detail, clientIp()));
        log.info("audit action={} user={} entity={}:{}", action, userId, entityType, entityId);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr();
        }
        return null;
    }
}
