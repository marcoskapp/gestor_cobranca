package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.Overhead;
import br.com.gestorcobranca.costing.domain.repository.OverheadRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface OverheadJpaRepository extends JpaRepository<Overhead, UUID>, OverheadRepository {
}
