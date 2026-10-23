package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;

import java.util.Collection;
import java.util.List;
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
}
