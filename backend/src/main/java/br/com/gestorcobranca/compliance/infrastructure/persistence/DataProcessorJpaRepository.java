package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.DataProcessor;
import br.com.gestorcobranca.compliance.domain.repository.DataProcessorRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DataProcessorJpaRepository extends JpaRepository<DataProcessor, UUID>, DataProcessorRepository {
}
