package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorResponseRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeCreditorResponseJpaRepository extends JpaRepository<DisputeCreditorResponse, UUID>, DisputeCreditorResponseRepository {
}
