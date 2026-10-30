package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.ContractIntegrationPricing;
import br.com.gestorcobranca.organization.domain.repository.ContractIntegrationPricingRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ContractIntegrationPricingJpaRepository extends JpaRepository<ContractIntegrationPricing, UUID>,
        ContractIntegrationPricingRepository {

    @Override
    @Query("select coalesce(max(p.pricingVersion), 0) from ContractIntegrationPricing p where p.contractId = :contractId")
    int maxVersion(UUID contractId);

    @Override
    @Query("""
            select p from ContractIntegrationPricing p where p.creditorId = :creditorId and p.pricingVersion =
                (select min(q.pricingVersion) from ContractIntegrationPricing q where q.contractId = p.contractId)""")
    List<ContractIntegrationPricing> findFirstVersionsByCreditorId(UUID creditorId);

    @Override
    @Query("select distinct p.creditorId from ContractIntegrationPricing p order by p.creditorId")
    List<UUID> findDistinctCreditorIds();
}
