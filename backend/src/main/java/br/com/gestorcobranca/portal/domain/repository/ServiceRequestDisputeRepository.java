package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.ServiceRequestDispute;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestDisputeRepository {

    ServiceRequestDispute save(ServiceRequestDispute dispute);

    Optional<ServiceRequestDispute> findByRequestId(UUID requestId);

    List<ServiceRequestDispute> findByRequestIdIn(Collection<UUID> requestIds);
}
