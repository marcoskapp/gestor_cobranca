package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.TitleOwnership;
import br.com.gestorcobranca.debt.domain.repository.TitleOwnershipRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TitleOwnershipJpaRepository extends JpaRepository<TitleOwnership, UUID>, TitleOwnershipRepository {

    @Override
    Optional<TitleOwnership> findByTitleIdAndReleasedAtIsNull(UUID titleId);

    @Override
    List<TitleOwnership> findByTitleIdOrderByAssignedAtAsc(UUID titleId);
}
