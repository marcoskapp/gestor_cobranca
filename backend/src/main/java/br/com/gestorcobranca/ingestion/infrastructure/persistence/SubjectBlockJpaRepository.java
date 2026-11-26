package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;
import br.com.gestorcobranca.ingestion.domain.repository.SubjectBlockRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Índice: {@code ix_import_subject_block_batch}; unicidade por lote e evento ({@code uk_import_subject_block}). */
interface SubjectBlockJpaRepository extends JpaRepository<SubjectBlock, UUID>, SubjectBlockRepository {
}
