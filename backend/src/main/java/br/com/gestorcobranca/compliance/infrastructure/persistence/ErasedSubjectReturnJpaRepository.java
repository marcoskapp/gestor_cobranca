package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectReturnRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ErasedSubjectReturnJpaRepository extends JpaRepository<ErasedSubjectReturn, UUID>,
        ErasedSubjectReturnRepository {

    @Override
    @Query("""
            select distinct r.requestId from ErasedSubjectReturn r
             where r.requestId in :requestIds and r.outcome is null
            """)
    List<UUID> findRequestIdsPendingReviewIn(Collection<UUID> requestIds);
}
