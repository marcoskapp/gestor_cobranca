package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.FileRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRequestRepository {

    FileRequest save(FileRequest request);

    Optional<FileRequest> findByBatchId(UUID batchId);

    /** Pedidos de vários lotes numa consulta (evidência no aviso ao credor; {@code uk_import_file_request_batch}). */
    List<FileRequest> findByBatchIdIn(Collection<UUID> batchIds);
}
