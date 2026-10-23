package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
class OriginContractService implements OriginContractUseCases {

    private final OriginContractRepository contracts;
    private final OriginContractAccess contractAccess;
    private final PrescriptionRecalculator prescriptions;
    private final AuditTrail auditTrail;

    OriginContractService(OriginContractRepository contracts, OriginContractAccess contractAccess,
                          PrescriptionRecalculator prescriptions, AuditTrail auditTrail) {
        this.contracts = contracts;
        this.contractAccess = contractAccess;
        this.prescriptions = prescriptions;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView get(UUID contractId) {
        return DebtViews.contract(contractAccess.requireVisible(contractId));
    }

    @Override
    public ContractView classify(UUID contractId, ClassifyContractCommand command) {
        OriginContract contract = contractAccess.lockForCommand(contractId);
        InstrumentType type = DebtViews.parseEnum(InstrumentType.class, command.instrumentType(),
                "origin_contract.instrument_type_invalid", "Natureza do título");
        classify(contract, type, command.executiveTitle(), "MANUAL");
        return DebtViews.contract(contract);
    }

    /** Reclassifica, audita e recalcula a prescrição dos títulos (também usado pela carga). @return true se mudou */
    boolean classify(OriginContract contract, InstrumentType type, boolean executiveTitle, String source) {
        InstrumentType previousType = contract.getInstrumentType();
        boolean previousExecutive = contract.isExecutiveTitle();
        if (!contract.classify(type, executiveTitle)) {
            return false;
        }
        contracts.save(contract);
        auditTrail.record(AuditEntry.of("origin_contract.classified", OriginContractAccess.RESOURCE, contract.getId())
                .creditor(contract.getCreditorId())
                .change("instrumentType", previousType, contract.getInstrumentType())
                .change("executiveTitle", previousExecutive, contract.isExecutiveTitle())
                .meta("source", source));
        if (previousType != contract.getInstrumentType()) {
            prescriptions.refreshContract(contract.getId());
        }
        return true;
    }
}
