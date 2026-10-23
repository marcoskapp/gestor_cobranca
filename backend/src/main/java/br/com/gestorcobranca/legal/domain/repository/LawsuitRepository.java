package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LawsuitStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LawsuitRepository {

    Lawsuit save(Lawsuit lawsuit);

    Optional<Lawsuit> findById(UUID id);

    List<Lawsuit> findByMatterIdOrderByFiledOnAscCreatedAtAsc(UUID matterId);

    boolean existsByCnjNumber(String cnjNumber);

    boolean existsByMatterId(UUID matterId);

    /** Processos em andamento para a captura de andamentos (cursor por id). */
    List<Lawsuit> findByStatusAndIdGreaterThanOrderByIdAsc(LawsuitStatus status, UUID afterId, Pageable page);
}
