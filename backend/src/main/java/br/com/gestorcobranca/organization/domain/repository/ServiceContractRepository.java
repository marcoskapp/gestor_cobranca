package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.ServiceContract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceContractRepository {

    ServiceContract save(ServiceContract contract);

    Optional<ServiceContract> findById(UUID id);

    List<ServiceContract> findByCreditorId(UUID creditorId);

    boolean existsByCreditorIdAndNumber(UUID creditorId, String number);
}
