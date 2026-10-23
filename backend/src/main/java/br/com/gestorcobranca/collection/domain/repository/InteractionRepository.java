package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.model.InteractionSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface InteractionRepository {

    Interaction save(Interaction interaction);

    Optional<Interaction> findById(UUID id);

    Optional<Interaction> findBySourceAndExternalRef(InteractionSource source, String externalRef);

    Page<Interaction> findByCaseIdOrderByStartedAtDesc(UUID caseId, Pageable pageable);
}
