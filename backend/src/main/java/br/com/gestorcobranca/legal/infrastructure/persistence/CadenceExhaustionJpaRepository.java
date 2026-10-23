package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.CadenceExhaustion;
import br.com.gestorcobranca.legal.domain.repository.CadenceExhaustionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CadenceExhaustionJpaRepository extends JpaRepository<CadenceExhaustion, UUID>, CadenceExhaustionRepository {
}
