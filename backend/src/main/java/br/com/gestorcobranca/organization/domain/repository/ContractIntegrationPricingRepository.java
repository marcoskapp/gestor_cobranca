package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.ContractIntegrationPricing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractIntegrationPricingRepository {

    ContractIntegrationPricing save(ContractIntegrationPricing pricing);

    /** Versões do contrato, da mais recente para a mais antiga. */
    List<ContractIntegrationPricing> findByContractIdOrderByPricingVersionDesc(UUID contractId);

    /** Versão mais recente registrada antes do instante (a que vale para um ciclo que começa nele). */
    Optional<ContractIntegrationPricing> findFirstByContractIdAndRegisteredAtBeforeOrderByPricingVersionDesc(
            UUID contractId, Instant before);

    int maxVersion(UUID contractId);

    /** Primeira versão (a de menor número) de cada contrato do credor que tem condições registradas. */
    List<ContractIntegrationPricing> findFirstVersionsByCreditorId(UUID creditorId);

    /** Credores com condições registradas em algum contrato. */
    List<UUID> findDistinctCreditorIds();
}
