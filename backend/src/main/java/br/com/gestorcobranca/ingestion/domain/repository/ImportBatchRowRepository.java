package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;

import java.util.Collection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRowRepository {

    ImportBatchRow save(ImportBatchRow row);

    <S extends ImportBatchRow> List<S> saveAll(Iterable<S> rows);

    List<ImportBatchRow> findByBatchIdAndExternalIdIn(UUID batchId, Collection<String> externalIds);

    /** Devedores criados pelo lote. */
    long countCreatedDebtors(UUID batchId);

    /** Devedores já existentes que o lote alterou (dados ou contatos). */
    long countUpdatedDebtors(UUID batchId);

    /** Devedores com títulos criados ou alterados pelo lote. */
    long countDebtorsWithChangedTitles(UUID batchId);

    /**
     * Títulos já carregados no credor com o identificador externo, do registro mais recente ao
     * mais antigo (todo título nasce de uma carga, então o histórico de cargas os alcança).
     */
    List<UUID> findTitleIds(UUID creditorId, String externalId, Pageable pageable);

    default Optional<UUID> latestTitleId(UUID creditorId, String externalId) {
        return findTitleIds(creditorId, externalId, PageRequest.of(0, 1)).stream().findFirst();
    }
}
