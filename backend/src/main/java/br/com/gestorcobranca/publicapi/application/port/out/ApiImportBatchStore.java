package br.com.gestorcobranca.publicapi.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Lotes de importação criados por token ({@code publicapi.api_import_batch}): permitem atribuir ao token os registros
 * processados de forma assíncrona pela ingestão (evento {@code PortfolioLoaded}).
 */
public interface ApiImportBatchStore {

    void register(UUID batchId, UUID tokenId, UUID creditorId);

    Optional<Origin> find(UUID batchId);

    record Origin(UUID tokenId, UUID creditorId) {
    }
}
