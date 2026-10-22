package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.Team;
import br.com.gestorcobranca.identity.domain.repository.TeamRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface TeamJpaRepository extends JpaRepository<Team, UUID>, TeamRepository {

    @Override
    List<Team> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("select t from Team t join t.memberIds m where m = :userId")
    List<Team> findByMember(UUID userId);
}
