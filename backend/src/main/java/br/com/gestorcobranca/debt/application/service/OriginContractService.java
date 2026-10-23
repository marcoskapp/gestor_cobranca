package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
class OriginContractService implements OriginContractUseCases {

    private final OriginContractAccess contractAccess;
    private final ContractClassifier classifier;

    OriginContractService(OriginContractAccess contractAccess, ContractClassifier classifier) {
        this.contractAccess = contractAccess;
        this.classifier = classifier;
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
        classifier.classify(contract, type, command.executiveTitle(), ChangeSource.MANUAL);
        return DebtViews.contract(contract);
    }
}
