package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.FileRequest;
import br.com.gestorcobranca.ingestion.domain.repository.FileRequestRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Unicidade por lote: {@code uk_import_file_request_batch} (V2307). */
interface FileRequestJpaRepository extends JpaRepository<FileRequest, UUID>, FileRequestRepository {
}
