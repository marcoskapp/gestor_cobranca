package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.service.CollateralRecorder.Lien;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.AppraisalSource;
import br.com.gestorcobranca.debt.domain.model.AssetIdentification;
import br.com.gestorcobranca.debt.domain.model.AssetKind;
import br.com.gestorcobranca.debt.domain.model.Collateral;
import br.com.gestorcobranca.debt.domain.model.CollateralAppraisal;
import br.com.gestorcobranca.debt.domain.model.CollateralType;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.TitleBalanceSnapshot;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.model.TitleOwnership;
import br.com.gestorcobranca.debt.domain.repository.CollateralAppraisalRepository;
import br.com.gestorcobranca.debt.domain.repository.CollateralRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleBalanceSnapshotRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleLedgerEntryRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleOwnershipRepository;
import br.com.gestorcobranca.debt.domain.service.TitleLedger;
import br.com.gestorcobranca.debt.domain.service.TitleLedger.Posting;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gravação de cargas linha a linha (F04). Cada método roda na própria transação e é idempotente:
 * <ul>
 *   <li>devedor casado pelo blind index do documento; contatos deduplicados pelo blind index do valor;</li>
 *   <li>título casado por (credor, identificador externo) sob lock pessimista; a abertura e os
 *       ajustes de principal são lançamentos referenciados ao lote, únicos no razão;</li>
 *   <li>saldo informado gravado uma vez por lote, e só quando a posição muda;</li>
 *   <li>coobrigado casado por (contrato, pessoa, papel); garantia pelo identificador externo ou
 *       pelo bem; avaliação uma vez por lote e só quando difere da última.</li>
 * </ul>
 * A prescrição dos títulos do contrato é recalculada quando a carga cria o título, muda seu
 * vencimento/contrato ou reclassifica a natureza do contrato.
 * Duas linhas concorrentes que criem o mesmo devedor/título em transações paralelas resultam em
 * violação de unicidade numa delas; o reprocessamento da linha encontra o registro e o atualiza.
 * A permissão de importar ({@code IMPORT_EXECUTE}) é verificada pelo módulo ingestion.
 */
@Service
@Transactional
class DebtIngestionService implements DebtIngestionPort {

    static final String LOCKED_STATUS = "title.locked_status";
    static final String PORTFOLIO_MISMATCH = "title.portfolio_mismatch";
    static final String PARTY_ENDED = "obligation_party.ended";
    static final String COLLATERAL_TYPE_MISMATCH = "collateral.type_mismatch";
    private static final String ADJUSTMENT_REASON = "Principal corrigido pelo credor na carga";

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final TitleLedgerEntryRepository ledger;
    private final TitleBalanceSnapshotRepository snapshots;
    private final TitleOwnershipRepository ownerships;
    private final CollateralRepository collaterals;
    private final CollateralAppraisalRepository appraisals;
    private final ObligationPartyRepository parties;
    private final DebtorRegistry debtorRegistry;
    private final LedgerRecorder ledgerRecorder;
    private final OriginContractAccess contractAccess;
    private final ContractClassifier classifier;
    private final ObligationPartyRecorder partyRecorder;
    private final CollateralRecorder collateralRecorder;
    private final PrescriptionRecalculator prescriptions;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DebtIngestionService(DebtorRepository debtors, DebtTitleRepository titles, OriginContractRepository contracts,
                         TitleLedgerEntryRepository ledger, TitleBalanceSnapshotRepository snapshots,
                         TitleOwnershipRepository ownerships, CollateralRepository collaterals,
                         CollateralAppraisalRepository appraisals, ObligationPartyRepository parties,
                         DebtorRegistry debtorRegistry, LedgerRecorder ledgerRecorder,
                         OriginContractAccess contractAccess, ContractClassifier classifier,
                         ObligationPartyRecorder partyRecorder, CollateralRecorder collateralRecorder,
                         PrescriptionRecalculator prescriptions, PortfolioUseCases portfolios,
                         AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                         BusinessEventLogger businessEvents, ApplicationEventPublisher events, Clock clock) {
        this.debtors = debtors;
        this.titles = titles;
        this.contracts = contracts;
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.ownerships = ownerships;
        this.collaterals = collaterals;
        this.appraisals = appraisals;
        this.parties = parties;
        this.debtorRegistry = debtorRegistry;
        this.ledgerRecorder = ledgerRecorder;
        this.contractAccess = contractAccess;
        this.classifier = classifier;
        this.partyRecorder = partyRecorder;
        this.collateralRecorder = collateralRecorder;
        this.prescriptions = prescriptions;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- devedor

    @Override
    public UpsertResult upsertDebtor(DebtorRecord record) {
        return debtorRegistry.upsertFromImport(record);
    }

    // ---------------------------------------------------------------- título

    @Override
    public UpsertResult upsertTitle(TitleRecord record, UUID importBatchId) {
        Guard.require(importBatchId != null, "title.import_batch_required", "Lote de importação obrigatório");
        Guard.require(record.creditorId() != null, "title.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(record.creditorId());
        PortfolioView portfolio = portfolios.requireAcceptingImports(record.portfolioId());
        Guard.require(portfolio.creditorId().equals(record.creditorId()), "title.portfolio_creditor_mismatch",
                "Carteira não pertence ao credor informado");
        Debtor debtor = debtors.findById(record.debtorId())
                .orElseThrow(() -> ResourceNotFoundException.of("debtor", record.debtorId()));
        Guard.requireState(!debtor.isAnonymized(), "debtor.anonymized", "Devedor anonimizado não recebe títulos");
        Guard.require(record.principalAmount() != null, "title.principal_invalid", "Valor principal obrigatório");
        Guard.require(record.reportedBalance() == null || record.balanceReferenceDate() != null,
                "title.balance_reference_date_required", "Data-base do saldo informado obrigatória");

        Instant now = Instant.now(clock);
        UUID contractId = upsertContract(record, debtor).map(OriginContract::getId).orElse(null);
        Money principal = Money.of(record.principalAmount());
        String externalId = record.externalId() == null ? null : record.externalId().trim();
        Optional<DebtTitle> existing = externalId == null ? Optional.empty()
                : titles.findByCreditorIdAndExternalIdForUpdate(record.creditorId(), externalId);

        List<String> warnings = new ArrayList<>();
        Outcome outcome;
        DebtTitle title;
        UUID previousContractId = null;
        LocalDate previousDueDate = null;
        if (existing.isEmpty()) {
            title = createTitle(record, debtor, contractId, principal, importBatchId, now);
            outcome = Outcome.CREATED;
        } else {
            title = existing.get();
            previousContractId = title.getOriginContractId();
            previousDueDate = title.getDueDate();
            outcome = updateTitle(title, record, contractId, principal, importBatchId, now, warnings);
        }
        if (outcome == Outcome.CREATED || !title.getDueDate().equals(previousDueDate)
                || !Objects.equals(previousContractId, title.getOriginContractId())) {
            // vencimento e contrato definem data-base e regras; o contrato anterior perde um título
            prescriptions.refreshFor(title, PrescriptionRecalculator.TRIGGER_IMPORT);
            if (previousContractId != null && !previousContractId.equals(title.getOriginContractId())) {
                prescriptions.refreshContract(previousContractId, PrescriptionRecalculator.TRIGGER_IMPORT);
            }
        }
        recordReportedBalance(title, record, importBatchId);
        return new UpsertResult(title.getId(), outcome, warnings);
    }

    private DebtTitle createTitle(TitleRecord record, Debtor debtor, UUID contractId, Money principal,
                                  UUID importBatchId, Instant now) {
        boolean consolidated = !titles.existsByDebtorIdAndCreditorId(debtor.getId(), record.creditorId())
                && titles.existsByDebtorIdAndCreditorIdNot(debtor.getId(), record.creditorId());
        DebtTitle title = new DebtTitle(record.creditorId(), record.portfolioId(), debtor.getId(), contractId,
                record.externalId(), record.installmentNumber(), record.dueDate(), principal, importBatchId);
        Posting opening = TitleLedger.open(title, now, TitleLedgerEntry.REF_IMPORT_BATCH, importBatchId);
        titles.save(title);
        ledger.saveAll(opening.entries());
        ownerships.save(new TitleOwnership(title.getId(), title.getCreditorId(), title.getPortfolioId(), now));
        auditTrail.record(AuditEntry.of("debt_title.created", "debt_title", title.getId())
                .creditor(title.getCreditorId())
                .created("externalId", title.getExternalId())
                .created("portfolioId", title.getPortfolioId())
                .created("debtorId", title.getDebtorId())
                .created("dueDate", title.getDueDate())
                .created("principalAmount", title.getPrincipalAmount())
                .created("status", title.getStatus())
                .meta("importBatchId", importBatchId));
        if (consolidated) {
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.DEBTOR_CONSOLIDATED)
                    .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(debtor.getId())
                    .reference("debt_title", title.getId()).amount(title.getPrincipalAmount())
                    .attr("importBatchId", importBatchId));
        }
        return title;
    }

    private Outcome updateTitle(DebtTitle title, TitleRecord record, UUID contractId, Money principal,
                                UUID importBatchId, Instant now, List<String> warnings) {
        Guard.require(title.getDebtorId().equals(record.debtorId()), "title.debtor_mismatch",
                "Título já pertence a outro devedor; o identificador externo não pode mudar de titular");
        if (!title.getPortfolioId().equals(record.portfolioId())) {
            // mudança de carteira é operação explícita (transferência), não efeito colateral da carga
            warnings.add(PORTFOLIO_MISMATCH);
        }
        if (title.isLockedForImport()) {
            warnings.add(LOCKED_STATUS);
            return Outcome.UNCHANGED;
        }
        LocalDate previousDueDate = title.getDueDate();
        UUID previousContractId = title.getOriginContractId();
        Integer previousInstallment = title.getInstallmentNumber();
        boolean termsChanged = title.updateTerms(contractId, record.installmentNumber(), record.dueDate());
        Money previousPrincipal = title.getPrincipalAmount();
        Posting adjustment = TitleLedger.adjustPrincipal(title, ledgerRecorder.existingEntries(title), principal, now,
                TitleLedgerEntry.REF_IMPORT_BATCH, importBatchId, ADJUSTMENT_REASON);
        if (!termsChanged && !adjustment.applied()) {
            return Outcome.UNCHANGED;
        }
        title.touchedByImport(importBatchId);
        titles.save(title);
        if (termsChanged) {
            auditTrail.record(AuditEntry.of("debt_title.updated", "debt_title", title.getId())
                    .creditor(title.getCreditorId())
                    .change("dueDate", previousDueDate, title.getDueDate())
                    .change("installmentNumber", previousInstallment, title.getInstallmentNumber())
                    .change("originContractId", previousContractId, title.getOriginContractId())
                    .meta("importBatchId", importBatchId));
        }
        if (adjustment.applied()) {
            ledgerRecorder.record(title, adjustment, "debt_title.principal_adjusted", Trigger.IMPORT);
            auditTrail.record(AuditEntry.of("debt_title.principal_changed", "debt_title", title.getId())
                    .creditor(title.getCreditorId())
                    .change("principalAmount", previousPrincipal, title.getPrincipalAmount())
                    .meta("importBatchId", importBatchId));
        }
        return Outcome.UPDATED;
    }

    private Optional<OriginContract> upsertContract(TitleRecord record, Debtor debtor) {
        ContractRecord c = record.contract();
        if (c == null) {
            return Optional.empty();
        }
        Money original = c.originalAmount() == null ? null : Money.of(c.originalAmount());
        String externalId = c.externalId() == null ? null : c.externalId().trim();
        Optional<OriginContract> existing = externalId == null ? Optional.empty()
                : contracts.findByCreditorIdAndExternalId(record.creditorId(), externalId);
        if (existing.isEmpty()) {
            OriginContract created = contracts.save(new OriginContract(record.creditorId(), record.portfolioId(),
                    debtor.getId(), c.externalId(), c.product(), c.signedAt(), original));
            auditTrail.record(AuditEntry.of("origin_contract.created", OriginContractAccess.RESOURCE, created.getId())
                    .creditor(created.getCreditorId())
                    .created("externalId", created.getExternalId())
                    .created("debtorId", created.getDebtorId())
                    .created("originalAmount", created.getOriginalAmount())
                    .created("instrumentType", created.getInstrumentType()));
            return Optional.of(created);
        }
        OriginContract contract = existing.get();
        Guard.require(contract.getDebtorId().equals(debtor.getId()), "origin_contract.debtor_mismatch",
                "Contrato de origem já pertence a outro devedor");
        String previousProduct = contract.getProduct();
        Money previousAmount = contract.getOriginalAmount();
        if (contract.update(c.product(), c.signedAt(), original)) {
            contracts.save(contract);
            auditTrail.record(AuditEntry.of("origin_contract.updated", OriginContractAccess.RESOURCE, contract.getId())
                    .creditor(contract.getCreditorId())
                    .change("product", previousProduct, contract.getProduct())
                    .change("originalAmount", previousAmount, contract.getOriginalAmount()));
        }
        return Optional.of(contract);
    }

    /** Saldo informado: uma vez por lote e só quando a posição muda em relação à última informada. */
    private void recordReportedBalance(DebtTitle title, TitleRecord record, UUID importBatchId) {
        if (record.reportedBalance() == null || snapshots.existsByTitleIdAndImportBatchId(title.getId(), importBatchId)) {
            return;
        }
        Money reported = Money.of(record.reportedBalance());
        boolean samePosition = snapshots.findFirstByTitleIdOrderByReferenceDateDescCreatedAtDesc(title.getId())
                .map(s -> s.samePositionAs(record.balanceReferenceDate(), reported)).orElse(false);
        if (!samePosition) {
            snapshots.save(new TitleBalanceSnapshot(title.getId(), record.balanceReferenceDate(), reported, importBatchId));
        }
    }

    // ---------------------------------------------------------------- natureza do título

    @Override
    public UpsertResult classifyContract(ContractNatureRecord record) {
        OriginContract contract = lockedContract(record.creditorId(), record.contractExternalId());
        InstrumentType type = DebtViews.parseEnum(InstrumentType.class, record.instrumentType(),
                "origin_contract.instrument_type_invalid", "Natureza do título");
        boolean changed = classifier.classify(contract, type, record.executiveTitle(), ChangeSource.IMPORT);
        return new UpsertResult(contract.getId(), changed ? Outcome.UPDATED : Outcome.UNCHANGED, List.of());
    }

    // ---------------------------------------------------------------- coobrigados

    @Override
    public UpsertResult upsertObligationParty(ObligationPartyRecord record) {
        Guard.require(record.person() != null, "obligation_party.person_required", "Pessoa obrigatória");
        OriginContract contract = contractAccess.requireForImport(record.creditorId(), record.contractExternalId());
        ObligationRole role = DebtViews.parseEnum(ObligationRole.class, record.role(), "obligation_party.role_invalid",
                "Papel na obrigação");
        UpsertResult person = debtorRegistry.upsertFromImport(record.person());
        Debtor debtor = debtors.findById(person.id()).orElseThrow();
        List<String> warnings = new ArrayList<>(person.warnings());
        Optional<ObligationParty> existing = parties.findByOriginContractIdAndDebtorIdAndRole(contract.getId(),
                debtor.getId(), role);
        if (existing.isEmpty()) {
            ObligationParty party = partyRecorder.register(contract, debtor, role, record.sinceDate(), ChangeSource.IMPORT);
            return new UpsertResult(party.getId(), Outcome.CREATED, warnings);
        }
        ObligationParty party = existing.get();
        if (!party.isActive()) {
            // participação encerrada (ex.: exoneração) não é reaberta pela carga
            warnings.add(PARTY_ENDED);
            return new UpsertResult(party.getId(), Outcome.UNCHANGED, warnings);
        }
        boolean changed = record.sinceDate() != null
                && partyRecorder.updateSince(party, record.sinceDate(), ChangeSource.IMPORT);
        return new UpsertResult(party.getId(), changed ? Outcome.UPDATED : Outcome.UNCHANGED, warnings);
    }

    // ---------------------------------------------------------------- garantias

    @Override
    public UpsertResult upsertCollateral(CollateralRecord record) {
        OriginContract contract = lockedContract(record.creditorId(), record.contractExternalId());
        CollateralType type = DebtViews.parseEnum(CollateralType.class, record.type(), "collateral.type_invalid",
                "Tipo de garantia");
        AssetIdentification asset = DebtViews.asset(record.assetKind(), record.plate(), record.renavam(),
                record.chassis(), record.manufactureYear(), record.modelYear(), record.registryNumber(),
                record.registryOffice(), record.municipality(), record.state());
        String externalId = record.externalId() == null || record.externalId().isBlank() ? null : record.externalId().trim();
        Guard.require(asset.getKind() != AssetKind.OTHER || externalId != null, "collateral.external_id_required",
                "Garantia sem identificação estruturada do bem exige identificador externo na carga");
        Lien lien = new Lien(record.registrationAuthority(), record.registrationNumber(), record.registrationDate());
        Set<UUID> titleIds = titleIds(contract, record.titleExternalIds());

        Optional<Collateral> existing = externalId == null ? Optional.empty()
                : collaterals.findByOriginContractIdAndExternalId(contract.getId(), externalId);
        if (existing.isEmpty()) {
            existing = collaterals.findSameAsset(contract.getId(), asset)
                    .filter(c -> c.getExternalId() == null || c.getExternalId().equals(externalId));
        }
        if (existing.isEmpty()) {
            Collateral created = collateralRecorder.register(contract, type, externalId, record.description(), asset,
                    lien, titleIds, ChangeSource.IMPORT);
            return new UpsertResult(created.getId(), Outcome.CREATED, List.of());
        }
        Collateral collateral = collaterals.findByIdForUpdate(existing.get().getId()).orElseThrow();
        List<String> warnings = new ArrayList<>();
        if (collateral.getType() != type) {
            // a modalidade não muda pela carga: a garantia é a mesma, a divergência vai ao relatório
            warnings.add(COLLATERAL_TYPE_MISMATCH);
        }
        boolean changed = collateralRecorder.update(contract, collateral, record.description(), asset, lien, titleIds,
                ChangeSource.IMPORT);
        return new UpsertResult(collateral.getId(), changed ? Outcome.UPDATED : Outcome.UNCHANGED, warnings);
    }

    @Override
    public UpsertResult recordCollateralAppraisal(CollateralAppraisalRecord record, UUID importBatchId) {
        Guard.require(importBatchId != null, "collateral.import_batch_required", "Lote de importação obrigatório");
        Guard.require(record.collateralId() != null, "collateral.collateral_required", "Garantia obrigatória");
        Collateral collateral = collaterals.findByIdForUpdate(record.collateralId())
                .orElseThrow(() -> ResourceNotFoundException.of(CollateralRecorder.ENTITY, record.collateralId()));
        accessScope.requireCreditor(collateral.getCreditorId());
        Guard.require(record.amount() != null, "collateral.appraisal_amount_invalid", "Valor da avaliação obrigatório");
        Guard.require(record.appraisalDate() == null || !record.appraisalDate().isAfter(DebtViews.today(clock)),
                "collateral.appraisal_date_future", "Data da avaliação não pode ser futura");
        AppraisalSource source = record.source() == null || record.source().isBlank() ? AppraisalSource.CREDITOR
                : DebtViews.parseEnum(AppraisalSource.class, record.source(), "collateral.appraisal_source_invalid",
                "Fonte da avaliação");
        Money amount = Money.of(record.amount());
        if (appraisals.existsByCollateralIdAndImportBatchId(collateral.getId(), importBatchId)) {
            return new UpsertResult(collateral.getId(), Outcome.UNCHANGED, List.of());
        }
        boolean samePosition = appraisals.findFirstByCollateralIdOrderByAppraisalDateDescCreatedAtDesc(collateral.getId())
                .map(a -> a.samePositionAs(amount, record.appraisalDate(), source)).orElse(false);
        if (samePosition) {
            return new UpsertResult(collateral.getId(), Outcome.UNCHANGED, List.of());
        }
        OriginContract contract = contracts.findById(collateral.getOriginContractId()).orElseThrow();
        collateralRecorder.appraise(contract, collateral, new CollateralAppraisal(collateral.getId(), amount,
                record.appraisalDate(), source, record.notes(), importBatchId), ChangeSource.IMPORT);
        return new UpsertResult(collateral.getId(), Outcome.UPDATED, List.of());
    }

    private OriginContract lockedContract(UUID creditorId, String contractExternalId) {
        OriginContract contract = contractAccess.requireForImport(creditorId, contractExternalId);
        return contracts.findByIdForUpdate(contract.getId()).orElseThrow();
    }

    /** Títulos cobertos, pelos identificadores externos, que devem ser parcelas do contrato. */
    private Set<UUID> titleIds(OriginContract contract, List<String> titleExternalIds) {
        if (titleExternalIds.isEmpty()) {
            return Set.of();
        }
        Set<String> requested = titleExternalIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim)
                .collect(Collectors.toSet());
        Map<String, DebtTitle> found = titles.findByCreditorIdAndExternalIdIn(contract.getCreditorId(), requested).stream()
                .collect(Collectors.toMap(DebtTitle::getExternalId, Function.identity()));
        if (!found.keySet().containsAll(requested)) {
            throw new BusinessRuleException("collateral.title_not_found",
                    "Título coberto pela garantia não encontrado no credor");
        }
        Set<UUID> ids = new HashSet<>();
        found.values().forEach(t -> ids.add(t.getId()));
        return ids;
    }

    // ---------------------------------------------------------------- fim do lote

    @Override
    public void completeImport(ImportSummary summary) {
        Guard.require(summary.importBatchId() != null, "title.import_batch_required", "Lote de importação obrigatório");
        accessScope.requireCreditor(summary.creditorId());
        events.publishEvent(new PortfolioLoaded(Ids.newId(), currentActor.tenantId(), Instant.now(clock),
                summary.importBatchId(), summary.creditorId(), summary.portfolioId(), summary.titlesCreated(),
                summary.titlesUpdated(), summary.debtorsAffected()));
    }
}
