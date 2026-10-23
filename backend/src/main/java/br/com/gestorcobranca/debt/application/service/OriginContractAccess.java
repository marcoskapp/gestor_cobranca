package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Carga de contratos de origem com escopo por credor (404 na leitura, 403 em comandos). */
@Component
class OriginContractAccess {

    static final String RESOURCE = "origin_contract";

    private final OriginContractRepository contracts;
    private final AccessScope accessScope;

    OriginContractAccess(OriginContractRepository contracts, AccessScope accessScope) {
        this.contracts = contracts;
        this.accessScope = accessScope;
    }

    OriginContract requireVisible(UUID contractId) {
        OriginContract contract = contracts.findById(contractId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, contractId));
        accessScope.requireVisible(RESOURCE, contractId, contract.getCreditorId());
        return contract;
    }

    OriginContract requireForCommand(UUID contractId) {
        OriginContract contract = requireVisible(contractId);
        accessScope.requireCreditor(contract.getCreditorId());
        return contract;
    }

    /** Com lock pessimista: reclassificação concorrente do mesmo contrato é serializada. */
    OriginContract lockForCommand(UUID contractId) {
        OriginContract contract = contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, contractId));
        accessScope.requireVisible(RESOURCE, contractId, contract.getCreditorId());
        accessScope.requireCreditor(contract.getCreditorId());
        return contract;
    }

    /** Contrato de uma carga, pelo identificador no credor. */
    OriginContract requireForImport(UUID creditorId, String contractExternalId) {
        Guard.require(creditorId != null, "origin_contract.creditor_required", "Credor obrigatório");
        Guard.require(contractExternalId != null && !contractExternalId.isBlank(), "origin_contract.external_id_required",
                "Identificador do contrato obrigatório");
        accessScope.requireCreditor(creditorId);
        return contracts.findByCreditorIdAndExternalId(creditorId, contractExternalId.trim())
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, contractExternalId.trim()));
    }
}
