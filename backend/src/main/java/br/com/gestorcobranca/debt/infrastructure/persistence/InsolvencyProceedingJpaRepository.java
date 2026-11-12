package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InsolvencyProceeding;
import br.com.gestorcobranca.debt.domain.model.InsolvencyStatus;
import br.com.gestorcobranca.debt.domain.repository.InsolvencyProceedingRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface InsolvencyProceedingJpaRepository extends JpaRepository<InsolvencyProceeding, UUID>,
        InsolvencyProceedingRepository {

    @Override
    List<InsolvencyProceeding> findByDebtorIdOrderByFiledOnDescCreatedAtDesc(UUID debtorId);

    @Override
    Optional<InsolvencyProceeding> findFirstByDebtorIdAndStatus(UUID debtorId, InsolvencyStatus status);

    @Override
    boolean existsByDebtorIdAndStatus(UUID debtorId, InsolvencyStatus status);
}
