package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.CollateralAppraisal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Avaliações de garantias: somente inclusão e leitura. */
public interface CollateralAppraisalRepository {

    CollateralAppraisal save(CollateralAppraisal appraisal);

    /** Histórico da mais recente para a mais antiga. */
    List<CollateralAppraisal> findByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(UUID collateralId);

    Optional<CollateralAppraisal> findFirstByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(UUID collateralId);

    boolean existsByCollateralIdAndImportBatchId(UUID collateralId, UUID importBatchId);
}
