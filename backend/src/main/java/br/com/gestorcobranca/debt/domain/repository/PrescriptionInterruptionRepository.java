package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.InterruptionType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionInterruption;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Interrupções de prescrição: somente inclusão e leitura. */
public interface PrescriptionInterruptionRepository {

    PrescriptionInterruption save(PrescriptionInterruption interruption);

    Optional<PrescriptionInterruption> findById(UUID id);

    List<PrescriptionInterruption> findByTitleIdOrderByInterruptionDateAscCreatedAtAsc(UUID titleId);

    Optional<PrescriptionInterruption> findByTitleIdAndTypeAndInterruptionDate(UUID titleId, InterruptionType type,
                                                                                LocalDate interruptionDate);
}
