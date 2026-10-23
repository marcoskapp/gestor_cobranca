package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.TitleBalanceSnapshot;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.model.TitleOwnership;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
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
import br.com.gestorcobranca.shared.domain.Document;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gravação de cargas linha a linha (F04). Cada método roda na própria transação e é idempotente:
 * <ul>
 *   <li>devedor casado pelo blind index do documento; contatos deduplicados pelo blind index do valor;</li>
 *   <li>título casado por (credor, identificador externo) sob lock pessimista; a abertura e os
 *       ajustes de principal são lançamentos referenciados ao lote, únicos no razão;</li>
 *   <li>saldo informado gravado uma vez por lote, e só quando a posição muda.</li>
 * </ul>
 * Duas linhas concorrentes que criem o mesmo devedor/título em transações paralelas resultam em
 * violação de unicidade numa delas; o reprocessamento da linha encontra o registro e o atualiza.
 * A permissão de importar ({@code IMPORT_EXECUTE}) é verificada pelo módulo ingestion.
 */
@Service
@Transactional
class DebtIngestionService implements DebtIngestionPort {

    static final String LOCKED_STATUS = "title.locked_status";
    static final String PORTFOLIO_MISMATCH = "title.portfolio_mismatch";
    private static final String ADJUSTMENT_REASON = "Principal corrigido pelo credor na carga";

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final TitleLedgerEntryRepository ledger;
    private final TitleBalanceSnapshotRepository snapshots;
    private final TitleOwnershipRepository ownerships;
    private final ContactRegistry contactRegistry;
    private final LedgerRecorder ledgerRecorder;
    private final PersonalDataIndex index;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DebtIngestionService(DebtorRepository debtors, DebtTitleRepository titles, OriginContractRepository contracts,
                         TitleLedgerEntryRepository ledger, TitleBalanceSnapshotRepository snapshots,
                         TitleOwnershipRepository ownerships, ContactRegistry contactRegistry,
                         LedgerRecorder ledgerRecorder, PersonalDataIndex index, PortfolioUseCases portfolios,
                         AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                         BusinessEventLogger businessEvents, ApplicationEventPublisher events, Clock clock) {
        this.debtors = debtors;
        this.titles = titles;
        this.contracts = contracts;
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.ownerships = ownerships;
        this.contactRegistry = contactRegistry;
        this.ledgerRecorder = ledgerRecorder;
        this.index = index;
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
        if (existing.isEmpty()) {
            title = createTitle(record, debtor, contractId, principal, importBatchId, now);
            outcome = Outcome.CREATED;
        } else {
            title = existing.get();
            outcome = updateTitle(title, record, contractId, principal, importBatchId, now, warnings);
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
            auditTrail.record(AuditEntry.of("origin_contract.created", "origin_contract", created.getId())
                    .creditor(created.getCreditorId())
                    .created("externalId", created.getExternalId())
                    .created("debtorId", created.getDebtorId())
                    .created("originalAmount", created.getOriginalAmount()));
            return Optional.of(created);
        }
        OriginContract contract = existing.get();
        Guard.require(contract.getDebtorId().equals(debtor.getId()), "origin_contract.debtor_mismatch",
                "Contrato de origem já pertence a outro devedor");
        String previousProduct = contract.getProduct();
        Money previousAmount = contract.getOriginalAmount();
        if (contract.update(c.product(), c.signedAt(), original)) {
            contracts.save(contract);
            auditTrail.record(AuditEntry.of("origin_contract.updated", "origin_contract", contract.getId())
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
