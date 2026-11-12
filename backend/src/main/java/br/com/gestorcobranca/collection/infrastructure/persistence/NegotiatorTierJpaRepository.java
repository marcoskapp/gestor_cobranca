package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.NegotiatorTier;
import br.com.gestorcobranca.collection.domain.repository.NegotiatorTierRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface NegotiatorTierJpaRepository extends JpaRepository<NegotiatorTier, UUID>, NegotiatorTierRepository {
}
