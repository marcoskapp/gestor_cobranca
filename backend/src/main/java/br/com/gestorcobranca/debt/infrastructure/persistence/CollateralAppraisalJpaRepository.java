package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.CollateralAppraisal;
import br.com.gestorcobranca.debt.domain.repository.CollateralAppraisalRepository;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem métodos de alteração/remoção: avaliações são append-only. */
interface CollateralAppraisalJpaRepository extends Repository<CollateralAppraisal, UUID>, CollateralAppraisalRepository {

    @Override
    CollateralAppraisal save(CollateralAppraisal appraisal);

    @Override
    List<CollateralAppraisal> findByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(UUID collateralId);

    @Override
    Optional<CollateralAppraisal> findFirstByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(UUID collateralId);

    @Override
    boolean existsByCollateralIdAndImportBatchId(UUID collateralId, UUID importBatchId);
}
