package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeMail;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeMailRepository {

    DisputeMail save(DisputeMail mail);

    DisputeMail saveAndFlush(DisputeMail mail);

    Optional<DisputeMail> findById(UUID id);

    List<DisputeMail> findByRequestIdIn(Collection<UUID> requestIds);
}
