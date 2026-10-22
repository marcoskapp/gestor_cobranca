package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleBalanceSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Saldos informados pelo credor: somente inclusão e leitura. */
public interface TitleBalanceSnapshotRepository {

    TitleBalanceSnapshot save(TitleBalanceSnapshot snapshot);

    Optional<TitleBalanceSnapshot> findFirstByTitleIdOrderByReferenceDateDescCreatedAtDesc(UUID titleId);

    boolean existsByTitleIdAndImportBatchId(UUID titleId, UUID importBatchId);

    List<TitleBalanceSnapshot> findTop12ByTitleIdOrderByReferenceDateDescCreatedAtDesc(UUID titleId);
}
