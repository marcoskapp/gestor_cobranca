package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.FileRequest;

import java.util.Optional;
import java.util.UUID;

public interface FileRequestRepository {

    FileRequest save(FileRequest request);

    Optional<FileRequest> findByBatchId(UUID batchId);
}
