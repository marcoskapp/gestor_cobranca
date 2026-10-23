package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrescriptionRuleJpaRepository extends JpaRepository<PrescriptionRule, UUID>, PrescriptionRuleRepository {

    @Override
    Optional<PrescriptionRule> findByInstrumentType(InstrumentType instrumentType);

    @Override
    List<PrescriptionRule> findAllByOrderByInstrumentTypeAsc();
}
