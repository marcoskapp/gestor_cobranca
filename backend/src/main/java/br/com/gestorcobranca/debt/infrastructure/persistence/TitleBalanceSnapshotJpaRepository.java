package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.TitleBalanceSnapshot;
import br.com.gestorcobranca.debt.domain.repository.TitleBalanceSnapshotRepository;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem métodos de alteração/remoção: registros de saldo informado são append-only. */
interface TitleBalanceSnapshotJpaRepository extends Repository<TitleBalanceSnapshot, UUID>,
        TitleBalanceSnapshotRepository {

    @Override
    TitleBalanceSnapshot save(TitleBalanceSnapshot snapshot);

    @Override
    Optional<TitleBalanceSnapshot> findFirstByTitleIdOrderByReferenceDateDescCreatedAtDesc(UUID titleId);

    @Override
    boolean existsByTitleIdAndImportBatchId(UUID titleId, UUID importBatchId);

    @Override
    List<TitleBalanceSnapshot> findTop12ByTitleIdOrderByReferenceDateDescCreatedAtDesc(UUID titleId);
}
