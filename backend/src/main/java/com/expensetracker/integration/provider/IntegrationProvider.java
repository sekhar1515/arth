package com.expensetracker.integration.provider;

import com.expensetracker.transaction.SourceType;

/**
 * Extension point for financial data sources. Providers turn their data into
 * {@link com.expensetracker.ingestion.RawTransaction}s and call the ingestion pipeline; the core engine never
 * depends on a specific provider. Register a new provider by adding a Spring bean.
 */
public interface IntegrationProvider {

    ProviderDescriptor descriptor();

    /** Source type recorded on transactions from this provider, or null if it does not create transactions. */
    SourceType sourceType();

    default String key() {
        return descriptor().key();
    }
}
