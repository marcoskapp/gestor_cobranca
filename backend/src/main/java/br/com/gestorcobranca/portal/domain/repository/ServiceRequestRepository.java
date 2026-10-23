package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestRepository {

    ServiceRequest save(ServiceRequest request);

    Optional<ServiceRequest> findById(UUID id);

    List<ServiceRequest> findByDebtorIdAndCreditorIdOrderByCreatedAtDesc(UUID debtorId, UUID creditorId);

    List<ServiceRequest> findByDebtorId(UUID debtorId);

    boolean existsByProtocol(String protocol);

    /** @param creditorIds credores permitidos (nulo = todos) */
    Page<ServiceRequest> search(Collection<UUID> creditorIds, UUID creditorId, ServiceRequestStatus status, Pageable pageable);
}
