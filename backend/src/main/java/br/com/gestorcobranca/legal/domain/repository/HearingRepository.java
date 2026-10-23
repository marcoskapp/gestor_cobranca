package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.Hearing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HearingRepository {

    Hearing save(Hearing hearing);

    Optional<Hearing> findById(UUID id);

    List<Hearing> findByLawsuitIdOrderByScheduledAtAsc(UUID lawsuitId);

    /** Audiências de todos os processos do caso jurídico, em ordem cronológica. */
    List<Hearing> findByMatterIdOrderByScheduledAtAscIdAsc(UUID matterId);
}
