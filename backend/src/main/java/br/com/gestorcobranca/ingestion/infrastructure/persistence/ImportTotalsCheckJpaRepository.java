package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.review.ImportTotalsCheck;
import br.com.gestorcobranca.ingestion.domain.repository.ImportTotalsCheckRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ImportTotalsCheckJpaRepository extends JpaRepository<ImportTotalsCheck, UUID>, ImportTotalsCheckRepository {
}
