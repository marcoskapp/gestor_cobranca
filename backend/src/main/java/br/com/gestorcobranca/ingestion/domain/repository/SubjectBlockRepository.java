package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;

import java.util.List;
import java.util.UUID;

public interface SubjectBlockRepository {

    SubjectBlock save(SubjectBlock block);

    boolean existsByBatchIdAndSourceEventId(UUID batchId, UUID sourceEventId);

    List<SubjectBlock> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    /** Bloqueios ligados ao aviso da eliminação ao credor (compliance), do mais antigo ao mais recente. */
    List<SubjectBlock> findByNoticeIdOrderByCreatedAtAsc(UUID noticeId);
}
