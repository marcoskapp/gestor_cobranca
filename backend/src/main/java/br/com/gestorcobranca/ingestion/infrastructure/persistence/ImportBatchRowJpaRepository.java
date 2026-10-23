package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRowRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

interface ImportBatchRowJpaRepository extends JpaRepository<ImportBatchRow, UUID>, ImportBatchRowRepository {

    @Override
    @Query("""
            select count(distinct r.debtorId) from ImportBatchRow r
            where r.batchId = :batchId and r.debtorOutcome = br.com.gestorcobranca.ingestion.domain.model.RowOutcome.CREATED
            """)
    long countCreatedDebtors(UUID batchId);

    @Override
    @Query("""
            select count(distinct r.debtorId) from ImportBatchRow r
            where r.batchId = :batchId and r.debtorOutcome = br.com.gestorcobranca.ingestion.domain.model.RowOutcome.UPDATED
              and r.debtorId not in (select c.debtorId from ImportBatchRow c where c.batchId = :batchId
                  and c.debtorOutcome = br.com.gestorcobranca.ingestion.domain.model.RowOutcome.CREATED)
            """)
    long countUpdatedDebtors(UUID batchId);

    @Override
    @Query("""
            select count(distinct r.debtorId) from ImportBatchRow r
            where r.batchId = :batchId and r.titleOutcome in (br.com.gestorcobranca.ingestion.domain.model.RowOutcome.CREATED,
                                                             br.com.gestorcobranca.ingestion.domain.model.RowOutcome.UPDATED)
            """)
    long countDebtorsWithChangedTitles(UUID batchId);
}
