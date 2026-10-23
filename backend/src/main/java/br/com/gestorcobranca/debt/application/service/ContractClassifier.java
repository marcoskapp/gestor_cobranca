package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;

/**
 * Classificação da natureza do título do contrato, comum ao cadastro manual e à carga: audita
 * e recalcula a prescrição dos títulos do contrato, na transação do chamador (que já validou o
 * escopo e travou o contrato).
 */
@Component
class ContractClassifier {

    private final OriginContractRepository contracts;
    private final PrescriptionRecalculator prescriptions;
    private final AuditTrail auditTrail;

    ContractClassifier(OriginContractRepository contracts, PrescriptionRecalculator prescriptions, AuditTrail auditTrail) {
        this.contracts = contracts;
        this.prescriptions = prescriptions;
        this.auditTrail = auditTrail;
    }

    /** @return true se a classificação mudou */
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
        // natureza e força executiva definem as regras aplicáveis a cada pretensão
        prescriptions.refreshContract(contract.getId(), PrescriptionRecalculator.TRIGGER_CLASSIFICATION);
        return true;
    }
}
