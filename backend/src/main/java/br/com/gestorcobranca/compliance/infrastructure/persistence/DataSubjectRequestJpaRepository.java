package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.RequestStatus;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DataSubjectRequestJpaRepository extends JpaRepository<DataSubjectRequest, UUID>,
        JpaSpecificationExecutor<DataSubjectRequest>, DataSubjectRequestRepository {

    @Override
    List<DataSubjectRequest> findByDebtorIdOrderByReceivedAtDesc(UUID debtorId);

    @Override
    default List<DataSubjectRequest> findOpenDueBefore(Instant now, int limit) {
        return findOpenDueBefore(now, Limit.of(limit));
    }

    @Query("""
            select r from DataSubjectRequest r
             where r.status in (br.com.gestorcobranca.compliance.domain.model.RequestStatus.RECEIVED,
                                br.com.gestorcobranca.compliance.domain.model.RequestStatus.IDENTITY_VERIFIED,
                                br.com.gestorcobranca.compliance.domain.model.RequestStatus.IN_PROGRESS)
               and r.overdue = false and r.dueAt <= :now
             order by r.dueAt
            """)
    List<DataSubjectRequest> findOpenDueBefore(Instant now, Limit limit);

    @Override
    default Page<DataSubjectRequest> search(RequestStatus status, RequestType type, UUID debtorId, String documentHash,
                                            Boolean overdue, boolean onlyLinked, Pageable pageable) {
        Specification<DataSubjectRequest> spec = Specification.unrestricted();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (debtorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), debtorId));
        }
        if (documentHash != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("documentHash"), documentHash));
        }
        if (overdue != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("overdue"), overdue));
        }
        if (onlyLinked) {
            spec = spec.and((root, query, cb) -> cb.isNotNull(root.get("debtorId")));
        }
        return findAll(spec, pageable);
    }
}
