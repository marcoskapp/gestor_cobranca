package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases;
import br.com.gestorcobranca.debt.application.service.CollateralRecorder.Lien;
import br.com.gestorcobranca.debt.domain.model.AppraisalSource;
import br.com.gestorcobranca.debt.domain.model.AssetIdentification;
import br.com.gestorcobranca.debt.domain.model.Collateral;
import br.com.gestorcobranca.debt.domain.model.CollateralAppraisal;
import br.com.gestorcobranca.debt.domain.model.CollateralStatus;
import br.com.gestorcobranca.debt.domain.model.CollateralType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.CollateralAppraisalRepository;
import br.com.gestorcobranca.debt.domain.repository.CollateralRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class CollateralService implements CollateralUseCases {

    private final CollateralRepository collaterals;
    private final CollateralAppraisalRepository appraisals;
    private final OriginContractRepository contracts;
    private final OriginContractAccess contractAccess;
    private final CollateralRecorder recorder;
    private final AccessScope accessScope;
    private final Clock clock;

    CollateralService(CollateralRepository collaterals, CollateralAppraisalRepository appraisals,
                      OriginContractRepository contracts, OriginContractAccess contractAccess, CollateralRecorder recorder,
                      AccessScope accessScope, Clock clock) {
        this.collaterals = collaterals;
        this.appraisals = appraisals;
        this.contracts = contracts;
        this.contractAccess = contractAccess;
        this.recorder = recorder;
        this.accessScope = accessScope;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CollateralView> listByContract(UUID contractId) {
        contractAccess.requireVisible(contractId);
        return collaterals.findByOriginContractIdOrderByCreatedAtAsc(contractId).stream().map(DebtViews::collateral).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CollateralView get(UUID collateralId) {
        return DebtViews.collateral(loadVisible(collateralId));
    }

    @Override
    public CollateralView register(UUID contractId, CollateralCommand command) {
        OriginContract contract = contractAccess.requireForCommand(contractId);
        CollateralType type = DebtViews.parseEnum(CollateralType.class, command.type(), "collateral.type_invalid",
                "Tipo de garantia");
        return DebtViews.collateral(recorder.register(contract, type, command.externalId(), command.description(),
                asset(command), lien(command), command.titleIds(), ChangeSource.MANUAL));
    }

    @Override
    public CollateralView update(UUID collateralId, CollateralCommand command) {
        Collateral collateral = lockForCommand(collateralId);
        OriginContract contract = contractOf(collateral);
        recorder.update(contract, collateral, command.description(), asset(command), lien(command), command.titleIds(),
                ChangeSource.MANUAL);
        return DebtViews.collateral(collateral);
    }

    @Override
    public CollateralView changeStatus(UUID collateralId, String status, String reason) {
        Collateral collateral = lockForCommand(collateralId);
        CollateralStatus target = DebtViews.parseEnum(CollateralStatus.class, status, "collateral.status_invalid",
                "Situação da garantia");
        recorder.changeStatus(contractOf(collateral), collateral, target, reason);
        return DebtViews.collateral(collateral);
    }

    @Override
    public AppraisalView appraise(UUID collateralId, AppraiseCommand command) {
        Collateral collateral = lockForCommand(collateralId);
        Guard.require(command.amount() != null, "collateral.appraisal_amount_invalid", "Valor da avaliação obrigatório");
        Guard.require(command.appraisalDate() == null || !command.appraisalDate().isAfter(DebtViews.today(clock)),
                "collateral.appraisal_date_future", "Data da avaliação não pode ser futura");
        AppraisalSource source = DebtViews.parseEnum(AppraisalSource.class, command.source(),
                "collateral.appraisal_source_invalid", "Fonte da avaliação");
        CollateralAppraisal appraisal = new CollateralAppraisal(collateral.getId(), Money.of(command.amount()),
                command.appraisalDate(), source, command.notes(), null);
        return DebtViews.appraisal(recorder.appraise(contractOf(collateral), collateral, appraisal, ChangeSource.MANUAL));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppraisalView> appraisals(UUID collateralId) {
        loadVisible(collateralId);
        return appraisals.findByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(collateralId).stream()
                .map(DebtViews::appraisal).toList();
    }

    private static AssetIdentification asset(CollateralCommand command) {
        Guard.require(command.asset() != null, "collateral.asset_required", "Identificação do bem obrigatória");
        AssetCommand a = command.asset();
        return DebtViews.asset(a.kind(), a.plate(), a.renavam(), a.chassis(), a.manufactureYear(), a.modelYear(),
                a.registryNumber(), a.registryOffice(), a.municipality(), a.state());
    }

    private static Lien lien(CollateralCommand command) {
        RegistrationCommand r = command.registration();
        return r == null ? new Lien(null, null, null) : new Lien(r.authority(), r.number(), r.date());
    }

    private OriginContract contractOf(Collateral collateral) {
        return contracts.findById(collateral.getOriginContractId()).orElseThrow();
    }

    private Collateral loadVisible(UUID collateralId) {
        Collateral collateral = collaterals.findById(collateralId)
                .orElseThrow(() -> ResourceNotFoundException.of(CollateralRecorder.ENTITY, collateralId));
        accessScope.requireVisible(CollateralRecorder.ENTITY, collateralId, collateral.getCreditorId());
        return collateral;
    }

    private Collateral lockForCommand(UUID collateralId) {
        Collateral collateral = collaterals.findByIdForUpdate(collateralId)
                .orElseThrow(() -> ResourceNotFoundException.of(CollateralRecorder.ENTITY, collateralId));
        accessScope.requireVisible(CollateralRecorder.ENTITY, collateralId, collateral.getCreditorId());
        accessScope.requireCreditor(collateral.getCreditorId());
        return collateral;
    }
}
