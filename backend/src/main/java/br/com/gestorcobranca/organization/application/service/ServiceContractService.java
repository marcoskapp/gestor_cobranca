package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.domain.model.Creditor;
import br.com.gestorcobranca.organization.domain.model.ServiceContract;
import br.com.gestorcobranca.organization.domain.repository.CreditorRepository;
import br.com.gestorcobranca.organization.domain.repository.ServiceContractRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class ServiceContractService implements ServiceContractUseCases {

    private final ServiceContractRepository contracts;
    private final CreditorRepository creditors;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    ServiceContractService(ServiceContractRepository contracts, CreditorRepository creditors, AccessScope accessScope,
                           AuditTrail auditTrail) {
        this.contracts = contracts;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public ContractView create(CreateContractCommand command) {
        Creditor creditor = creditors.findById(command.creditorId())
                .orElseThrow(() -> ResourceNotFoundException.of("creditor", command.creditorId()));
        accessScope.requireCreditor(creditor.getId());
        if (command.number() != null && contracts.existsByCreditorIdAndNumber(creditor.getId(), command.number().trim())) {
            throw new ConflictException("contract.number_taken", "Já existe contrato com este número para o credor");
        }
        ContractTermsCommand terms = command.terms();
        ServiceContract contract = contracts.save(new ServiceContract(creditor.getId(), command.number(), terms.startDate(),
                terms.endDate(), terms.returnAfterDays(), terms.settlementDay(), terms.notes()));
        auditTrail.record(AuditEntry.of("contract.created", "service_contract", contract.getId())
                .creditor(creditor.getId())
                .created("number", contract.getNumber())
                .created("startDate", contract.getStartDate())
                .created("endDate", contract.getEndDate())
                .created("settlementDay", contract.getSettlementDay()));
        return view(contract);
    }

    @Override
    public ContractView updateTerms(UUID contractId, ContractTermsCommand terms) {
        ServiceContract contract = loadVisible(contractId);
        accessScope.requireCreditor(contract.getCreditorId());
        AuditEntry audit = AuditEntry.of("contract.terms_updated", "service_contract", contractId)
                .creditor(contract.getCreditorId())
                .change("startDate", contract.getStartDate(), terms.startDate())
                .change("endDate", contract.getEndDate(), terms.endDate())
                .change("returnAfterDays", contract.getReturnAfterDays(), terms.returnAfterDays())
                .change("settlementDay", contract.getSettlementDay(), terms.settlementDay());
        contract.defineTerms(terms.startDate(), terms.endDate(), terms.returnAfterDays(), terms.settlementDay(), terms.notes());
        contracts.save(contract);
        auditTrail.record(audit);
        return view(contract);
    }

    @Override
    public ContractView terminate(UUID contractId, LocalDate endDate, String reason) {
        ServiceContract contract = loadVisible(contractId);
        accessScope.requireCreditor(contract.getCreditorId());
        String previous = contract.getStatus().name();
        contract.terminate(endDate);
        contracts.save(contract);
        auditTrail.record(AuditEntry.of("contract.terminated", "service_contract", contractId)
                .creditor(contract.getCreditorId())
                .change("status", previous, contract.getStatus().name())
                .change("endDate", null, endDate)
                .reason(reason));
        return view(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView get(UUID contractId) {
        return view(loadVisible(contractId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> listByCreditor(UUID creditorId) {
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return contracts.findByCreditorId(creditorId).stream()
                .sorted(Comparator.comparing(ServiceContract::getStartDate).reversed())
                .map(ServiceContractService::view)
                .toList();
    }

    private ServiceContract loadVisible(UUID contractId) {
        ServiceContract contract = contracts.findById(contractId)
                .orElseThrow(() -> ResourceNotFoundException.of("service_contract", contractId));
        accessScope.requireVisible("service_contract", contractId, contract.getCreditorId());
        return contract;
    }

    static ContractView view(ServiceContract c) {
        return new ContractView(c.getId(), c.getCreditorId(), c.getNumber(), c.getStartDate(), c.getEndDate(),
                c.getReturnAfterDays(), c.getSettlementDay(), c.getNotes(), c.getStatus().name());
    }
}
