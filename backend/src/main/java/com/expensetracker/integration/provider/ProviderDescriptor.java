package com.expensetracker.integration.provider;

/**
 * Honest description of an integration, shown on the Integrations page. Mirrors docs/FEASIBILITY.md.
 */
public record ProviderDescriptor(
        String key,
        String name,
        String description,
        Availability availability,
        ConnectionMode connectionMode,
        boolean requiresCommercialPartnership,
        boolean requiresRegulatoryApproval,
        boolean requiresUserConsent,
        String dataRetrieved,
        String syncFrequency,
        String limitation,
        String setupHint) {

    public enum Availability {
        /** Works now. */
        AVAILABLE,
        /** Planned, adapter boundary exists, not implemented in this release. */
        PLANNED,
        /** Not legitimately available to a personal app. */
        UNAVAILABLE
    }

    public enum ConnectionMode { BUILT_IN, FILE_UPLOAD, WEBHOOK_TOKEN, NONE }
}
