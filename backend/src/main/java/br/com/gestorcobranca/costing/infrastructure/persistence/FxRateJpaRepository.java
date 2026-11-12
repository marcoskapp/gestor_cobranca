package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.FxRate;
import br.com.gestorcobranca.costing.domain.repository.FxRateRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface FxRateJpaRepository extends JpaRepository<FxRate, UUID>, FxRateRepository {
}
