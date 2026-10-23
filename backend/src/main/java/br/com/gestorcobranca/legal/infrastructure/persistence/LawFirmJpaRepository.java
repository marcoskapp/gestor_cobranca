package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LawFirm;
import br.com.gestorcobranca.legal.domain.repository.LawFirmRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LawFirmJpaRepository extends JpaRepository<LawFirm, UUID>, LawFirmRepository {

    @Override
    Optional<LawFirm> findById(UUID id);
}
