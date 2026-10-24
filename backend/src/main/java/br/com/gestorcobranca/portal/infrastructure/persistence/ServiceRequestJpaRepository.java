package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestStatus;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ServiceRequestJpaRepository extends JpaRepository<ServiceRequest, UUID>, JpaSpecificationExecutor<ServiceRequest>,
        ServiceRequestRepository {

    @Override
    List<ServiceRequest> findByDebtorIdAndCreditorIdOrderByCreatedAtDesc(UUID debtorId, UUID creditorId);

    @Override
    List<ServiceRequest> findByDebtorId(UUID debtorId);

    @Override
    boolean existsByProtocol(String protocol);

    @Override
    default Page<ServiceRequest> search(Collection<UUID> creditorIds, UUID creditorId, ServiceRequestStatus status,
                                        Pageable pageable) {
        Specification<ServiceRequest> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return findAll(spec, pageable);
    }
}
