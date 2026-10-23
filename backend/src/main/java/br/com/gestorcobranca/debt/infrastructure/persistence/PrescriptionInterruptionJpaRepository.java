package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InterruptionType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionInterruption;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionInterruptionRepository;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem métodos de alteração/remoção: interrupções são append-only. */
interface PrescriptionInterruptionJpaRepository extends Repository<PrescriptionInterruption, UUID>,
        PrescriptionInterruptionRepository {

    @Override
    PrescriptionInterruption save(PrescriptionInterruption interruption);

    @Override
    Optional<PrescriptionInterruption> findById(UUID id);

    @Override
    List<PrescriptionInterruption> findByTitleIdOrderByInterruptionDateAscCreatedAtAsc(UUID titleId);

    @Override
    Optional<PrescriptionInterruption> findByTitleIdAndTypeAndInterruptionDate(UUID titleId, InterruptionType type,
                                                                                LocalDate interruptionDate);
}
