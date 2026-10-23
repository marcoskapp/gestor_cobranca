package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.ExtrajudicialMeasure;
import br.com.gestorcobranca.legal.domain.model.MeasureStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtrajudicialMeasureRepository {

    ExtrajudicialMeasure save(ExtrajudicialMeasure measure);

    Optional<ExtrajudicialMeasure> findById(UUID id);

    List<ExtrajudicialMeasure> findByMatterIdOrderByCreatedAtAsc(UUID matterId);

    List<ExtrajudicialMeasure> findByMatterIdAndStatusIn(UUID matterId, Collection<MeasureStatus> statuses);
}
