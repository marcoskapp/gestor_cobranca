package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.ServiceContract;
import br.com.gestorcobranca.organization.domain.repository.ServiceContractRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ServiceContractJpaRepository extends JpaRepository<ServiceContract, UUID>, ServiceContractRepository {

    @Override
    List<ServiceContract> findByCreditorId(UUID creditorId);

    @Override
    boolean existsByCreditorIdAndNumber(UUID creditorId, String number);
}
