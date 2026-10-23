package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.ImportRowIssue;
import br.com.gestorcobranca.ingestion.domain.repository.ImportRowIssueRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ImportRowIssueJpaRepository extends JpaRepository<ImportRowIssue, UUID>, ImportRowIssueRepository {
}
