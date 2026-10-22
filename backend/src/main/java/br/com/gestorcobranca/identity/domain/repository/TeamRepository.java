package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.Team;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository {

    Team save(Team team);

    Optional<Team> findById(UUID id);

    List<Team> findAll();

    List<Team> findAllByIdIn(Collection<UUID> ids);

    List<Team> findByMember(UUID userId);
}
