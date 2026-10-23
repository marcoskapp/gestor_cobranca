package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrescriptionRuleJpaRepository extends JpaRepository<PrescriptionRule, UUID>, PrescriptionRuleRepository {

    @Override
    List<PrescriptionRule> findAllByIdIn(Collection<UUID> ids);

    @Override
    Optional<PrescriptionRule> findByInstrumentTypeAndClaim(InstrumentType instrumentType, PrescriptionClaim claim);

    @Override
    List<PrescriptionRule> findByInstrumentType(InstrumentType instrumentType);

    @Override
    List<PrescriptionRule> findAllByOrderByInstrumentTypeAscClaimAsc();
}
