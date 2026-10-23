package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LawsuitJpaRepository extends JpaRepository<Lawsuit, UUID>, LawsuitRepository {

    @Override
    Optional<Lawsuit> findById(UUID id);
}
