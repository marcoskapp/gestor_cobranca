package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionRuleRepository {

    PrescriptionRule save(PrescriptionRule rule);

    Optional<PrescriptionRule> findById(UUID id);

    List<PrescriptionRule> findAllByIdIn(java.util.Collection<UUID> ids);

    Optional<PrescriptionRule> findByInstrumentTypeAndClaim(InstrumentType instrumentType, PrescriptionClaim claim);

    List<PrescriptionRule> findByInstrumentType(InstrumentType instrumentType);

    List<PrescriptionRule> findAllByOrderByInstrumentTypeAscClaimAsc();

    void delete(PrescriptionRule rule);
}
