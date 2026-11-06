package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceDownload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.debt.application.port.spi.TitleAgreementGuard;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentMode;
import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentReason;
import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentSettings;
import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentStatus;
import br.com.gestorcobranca.debt.domain.model.AmountDivergenceStatus;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.TitleAmountAdjustment;
import br.com.gestorcobranca.debt.domain.model.TitleAmountDivergence;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.repository.AmountAdjustmentSettingsRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountAdjustmentRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountDivergenceRepository;
import br.com.gestorcobranca.debt.domain.service.TitleBalance;
import br.com.gestorcobranca.debt.domain.service.TitleLedger;
import br.com.gestorcobranca.debt.domain.service.TitleLedger.Posting;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileSignatures;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Correção manual do valor do título (decisões 802–813). O título é travado (lock pessimista) em toda operação que mexe
 * no razão; a aprovação confere que os saldos ainda são os do pedido (senão, recusa e pede um pedido novo). O limite é
 * acumulado por título numa janela (a soma das reduções recentes é lida com o título travado) e a proposta de aumento do
 * limite vence (decisões do responsável sobre as 864 e 865). O pedido não tem transação longa: a evidência vai ao
 * armazenamento sem transação aberta e o registro acontece numa transação curta (decisão do responsável sobre a 872).
 */
@Service
@Transactional
class TitleAmountAdjustmentService implements TitleAmountAdjustmentUseCases {

    static final String TITLE = "debt_title";
    static final String RESOURCE = "title_amount_adjustment";
    static final String REF_ADJUSTMENT = "amount_adjustment";
    static final String REF_DIVERGENCE = "amount_divergence";

    private final DebtTitleRepository titles;
    private final LedgerRecorder ledgerRecorder;
    private final TitleAmountAdjustmentRepository adjustments;
    private final TitleAmountDivergenceRepository divergences;
    private final AmountAdjustmentSettingsRepository settings;
    private final ObjectProvider<TitleAgreementGuard> agreementGuard;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final AccessLogger accessLogger;
    private final FileStorage fileStorage;
    private final EvidenceFileCleanup fileCleanup;
    private final DebtProperties properties;
    private final DebtMetrics metrics;
    private final Clock clock;
    /** Transações curtas do pedido (o método roda sem transação; decisão do responsável sobre a 872). */
    private final TransactionTemplate shortTx;
    private final TransactionTemplate shortReadTx;

    TitleAmountAdjustmentService(DebtTitleRepository titles, LedgerRecorder ledgerRecorder,
                                 TitleAmountAdjustmentRepository adjustments, TitleAmountDivergenceRepository divergences,
                                 AmountAdjustmentSettingsRepository settings,
                                 ObjectProvider<TitleAgreementGuard> agreementGuard, AccessScope accessScope,
                                 CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                                 AccessLogger accessLogger, FileStorage fileStorage, EvidenceFileCleanup fileCleanup,
                                 DebtProperties properties, DebtMetrics metrics, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.titles = titles;
        this.ledgerRecorder = ledgerRecorder;
        this.adjustments = adjustments;
        this.divergences = divergences;
        this.settings = settings;
        this.agreementGuard = agreementGuard;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.accessLogger = accessLogger;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.shortTx = new TransactionTemplate(transactionManager);
        this.shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shortReadTx = new TransactionTemplate(transactionManager);
        this.shortReadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shortReadTx.setReadOnly(true);
    }

    // =================================================================== consultas

    @Override
    @Transactional(readOnly = true)
    public AdjustmentContext context(UUID titleId) {
        DebtTitle title = loadVisible(titleId);
        TitleBalance balance = TitleBalance.of(ledgerRecorder.existingEntries(title));
        List<ComponentAmount> components = Arrays.stream(LedgerComponent.values())
                .map(c -> new ComponentAmount(c.name(), balance.balanceOf(c).amount())).toList();
        Optional<UUID> agreement = activeAgreement(title);
        Optional<TitleAmountAdjustment> pending = adjustments.findByTitleIdOrderByRequestedAtDesc(titleId).stream()
                .filter(a -> a.getStatus() == AmountAdjustmentStatus.PENDING_APPROVAL).findFirst();
        List<String> blockers = new ArrayList<>();
        if (!title.getStatus().acceptsPostings()) {
            blockers.add("STATUS");
        }
        agreement.ifPresent(a -> blockers.add("AGREEMENT"));
        pending.ifPresent(p -> blockers.add("PENDING"));
        // prévia da alçada (sem lock): o pedido relê a soma com o título travado
        return new AdjustmentContext(titleId, components, title.getPrincipalAmount().amount(),
                title.getOpenBalance().amount(), threshold().amount(), title.isManualPrincipal(),
                title.getCreditorPrincipal().amount(), blockers, agreement.orElse(null),
                pending.map(a -> view(a, title)).orElse(null),
                divergences.findOpenByTitleId(titleId).map(d -> view(d, title)).orElse(null),
                recentReductions(titleId, Instant.now(clock)).amount(), cumulativeWindowDays());
    }

    @Override
    @Transactional(readOnly = true)
    public TitleAmountHistory history(UUID titleId) {
        DebtTitle title = loadVisible(titleId);
        return new TitleAmountHistory(titleId,
                adjustments.findByTitleIdOrderByRequestedAtDesc(titleId).stream().map(a -> view(a, title)).toList(),
                divergences.findByTitleIdOrderByDetectedAtDesc(titleId).stream().map(d -> view(d, title)).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdjustmentView> pendingApprovals(PageQuery page) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_APPROVE);
        Actor actor = currentActor.get();
        // a fila não mostra os próprios pedidos (o domínio também recusa a autoaprovação)
        return PageResponse.of(adjustments.findPending(creditorFilter(), accessScope.hasAllCreditors(), actor.id(),
                PageRequest.of(page.page(), page.size())), a -> view(a, titles.findById(a.getTitleId()).orElse(null)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DivergenceView> openDivergences(PageQuery page) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_ADJUST);
        return PageResponse.of(divergences.findOpen(creditorFilter(), accessScope.hasAllCreditors(),
                PageRequest.of(page.page(), page.size())), d -> view(d, titles.findById(d.getTitleId()).orElse(null)));
    }

    // =================================================================== pedido e decisão

    /**
     * Sem transação longa (decisão do responsável sobre a 872, no padrão do envio de PDF, decisão 870): o escopo é
     * conferido numa transação curta de leitura (com a recusa antecipada do pedido pendente), sem carregar nem travar o
     * título; a evidência vai ao armazenamento sem
     * transação aberta (não prende conexão nem a linha do título, que pagamentos, estornos e cargas também travam); o
     * registro acontece numa transação curta com o título travado. Se o registro é desfeito (falha ou a regra recusa o
     * pedido), o arquivo gravado é removido; se o commit pode ter acontecido (desfecho incerto), o arquivo é mantido.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdjustmentView request(UUID titleId, AdjustmentCommand command, EvidenceUpload upload) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_ADJUST);
        Guard.require(command != null, "title_amount.required", "Dados da correção obrigatórios");
        if (upload == null) {
            return shortTx.execute(s -> register(titleId, command, null));
        }
        shortReadTx.executeWithoutResult(s -> {
            UUID creditorId = titles.findCreditorIdById(titleId)
                    .orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
            accessScope.requireVisible(TITLE, titleId, creditorId);
            accessScope.requireCreditor(creditorId);
            // recusa barata antes do envio; o registro confere de novo com o título travado
            requireNoPending(titleId);
        });
        TitleAmountAdjustment.Evidence evidence = storeOutsideTransaction(upload);
        return fileCleanup.registerOrDiscard(evidence.key(), shortTx, () -> register(titleId, command, evidence));
    }

    private void requireNoPending(UUID titleId) {
        Guard.requireState(!adjustments.existsByTitleIdAndStatus(titleId, AmountAdjustmentStatus.PENDING_APPROVAL),
                "title_amount.pending_exists", "Já há um pedido de correção aguardando aprovação neste título");
    }

    /** Registro do pedido, na transação curta e com o título travado. */
    private AdjustmentView register(UUID titleId, AdjustmentCommand command, TitleAmountAdjustment.Evidence evidence) {
        DebtTitle title = lockForCommand(titleId);
        requireAdjustable(title);
        requireNoPending(titleId);
        AmountAdjustmentReason reason = DebtViews.parseEnum(AmountAdjustmentReason.class, command.reason(),
                "title_amount.reason_invalid", "Motivo");
        Map<LedgerComponent, Money> targets = new EnumMap<>(LedgerComponent.class);
        command.components().forEach((component, value) -> targets.put(
                DebtViews.parseEnum(LedgerComponent.class, component, "title_amount.component_invalid", "Componente"),
                value == null ? null : Money.of(value)));
        List<TitleLedgerEntry> existing = ledgerRecorder.existingEntries(title);
        Map<LedgerComponent, Money> balances = balances(existing);
        Instant now = Instant.now(clock);
        Actor actor = currentActor.get();
        // limite acumulado: lido depois do lock do título, então pedidos concorrentes no mesmo título se enxergam
        TitleAmountAdjustment adjustment = new TitleAmountAdjustment(title, balances, targets, reason, command.note(),
                command.disputeId(), evidence, threshold(), recentReductions(titleId, now), actor.id(), actor.name(),
                now);
        if (adjustment.getApprovalMode() == AmountAdjustmentMode.WITHIN_LIMIT) {
            adjustment.markApplied(actor.id(), actor.name(), null, now);
            TitleAmountAdjustment saved = adjustments.save(adjustment);
            apply(title, existing, saved, now);
            return view(saved, title);
        }
        TitleAmountAdjustment saved = adjustments.save(adjustment);
        auditTrail.record(AuditEntry.of("title_amount.requested", TITLE, title.getId())
                .creditor(title.getCreditorId())
                .reason(saved.getReasonCode().name())
                .meta("adjustmentId", saved.getId())
                .meta("delta", saved.getDelta())
                .meta("disputeId", saved.getDisputeId())
                .meta("evidenceSha256", evidence == null ? null : evidence.sha256())
                .meta("approvalMode", saved.getApprovalMode())
                .meta("cumulativeLimit", adjustment.isCumulativeLimitReached()));
        businessEvents.log(base(BusinessEventTypes.TITLE_AMOUNT_ADJUSTMENT_REQUESTED, title, saved)
                .attr("cumulativeLimit", adjustment.isCumulativeLimitReached()));
        metrics.amountAdjustment("requested", saved.getApprovalMode().name());
        if (adjustment.isCumulativeLimitReached()) {
            metrics.amountAdjustmentCumulativeLimit();
        }
        return view(saved, title);
    }

    @Override
    public AdjustmentView approve(UUID adjustmentId, String note) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_APPROVE);
        TitleAmountAdjustment adjustment = load(adjustmentId);
        DebtTitle title = lockForCommand(adjustment.getTitleId());
        requireAdjustable(title);
        List<TitleLedgerEntry> existing = ledgerRecorder.existingEntries(title);
        if (!adjustment.matchesCurrent(balances(existing), title)) {
            throw new ConflictException("title_amount.balances_changed",
                    "O saldo do título mudou depois do pedido (pagamento, carga ou outra correção). Recuse este pedido e"
                            + " peça de novo sobre os valores atuais.", Map.of("adjustmentId", adjustmentId.toString()));
        }
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        adjustment.markApplied(actor.id(), actor.name(), note, now);
        TitleAmountAdjustment saved = adjustments.save(adjustment);
        apply(title, existing, saved, now);
        return view(saved, title);
    }

    @Override
    public AdjustmentView reject(UUID adjustmentId, String note) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_APPROVE);
        TitleAmountAdjustment adjustment = load(adjustmentId);
        DebtTitle title = lockForCommand(adjustment.getTitleId());
        Actor actor = currentActor.get();
        adjustment.reject(actor.id(), actor.name(), note, Instant.now(clock));
        TitleAmountAdjustment saved = adjustments.save(adjustment);
        auditTrail.record(AuditEntry.of("title_amount.rejected", TITLE, title.getId())
                .creditor(title.getCreditorId()).reason(saved.getDecisionNote())
                .meta("adjustmentId", saved.getId())
                .meta("requestedBy", saved.getRequestedBy()));
        businessEvents.log(base(BusinessEventTypes.TITLE_AMOUNT_ADJUSTMENT_REJECTED, title, saved)
                .attr("hoursPending", Duration.between(saved.getRequestedAt(), saved.getDecidedAt()).toHours()));
        metrics.amountAdjustment("rejected", saved.getApprovalMode().name());
        return view(saved, title);
    }

    @Override
    public AdjustmentView cancel(UUID adjustmentId, String note) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_ADJUST);
        TitleAmountAdjustment adjustment = load(adjustmentId);
        DebtTitle title = lockForCommand(adjustment.getTitleId());
        Actor actor = currentActor.get();
        adjustment.cancel(actor.id(), actor.name(), note, Instant.now(clock));
        TitleAmountAdjustment saved = adjustments.save(adjustment);
        auditTrail.record(AuditEntry.of("title_amount.cancelled", TITLE, title.getId())
                .creditor(title.getCreditorId()).reason(saved.getDecisionNote())
                .meta("adjustmentId", saved.getId()));
        metrics.amountAdjustment("cancelled", saved.getApprovalMode().name());
        return view(saved, title);
    }

    /** Lança no razão (via {@code LedgerRecorder}: auditoria do lançamento e {@code TitleStatusChanged}). */
    private void apply(DebtTitle title, List<TitleLedgerEntry> existing, TitleAmountAdjustment adjustment, Instant now) {
        Money previousPrincipal = title.getPrincipalAmount();
        Posting posting = TitleLedger.adjustManually(title, existing, adjustment.deltas(), now, REF_ADJUSTMENT,
                adjustment.getId(), "Correção manual: " + adjustment.getReasonCode().name());
        ledgerRecorder.record(title, posting, "title_amount.applied", Trigger.AMOUNT_ADJUSTMENT);
        AuditEntry audit = AuditEntry.of("title_amount.adjusted", TITLE, title.getId())
                .creditor(title.getCreditorId())
                .reason(adjustment.getReasonCode().name())
                .change("principalAmount", previousPrincipal, title.getPrincipalAmount())
                .change("openBalance", adjustment.getPreviousOpenBalance(), title.getOpenBalance())
                .meta("adjustmentId", adjustment.getId())
                .meta("approvalMode", adjustment.getApprovalMode())
                .meta("requestedBy", adjustment.getRequestedBy())
                .meta("approvedBy", adjustment.getDecidedBy())
                .meta("disputeId", adjustment.getDisputeId())
                .meta("manualPrincipal", title.isManualPrincipal());
        adjustment.getLines().forEach(l -> audit.change("component." + l.getComponent().name(), l.getPreviousBalance(),
                l.getNewBalance()));
        auditTrail.record(audit);
        businessEvents.log(base(BusinessEventTypes.TITLE_AMOUNT_ADJUSTED, title, adjustment)
                .attr("components", adjustment.getLines().stream().map(l -> l.getComponent().name()).toList().toString())
                .attr("hoursPending", adjustment.getApprovalMode() == AmountAdjustmentMode.DUAL_CONTROL
                        ? Duration.between(adjustment.getRequestedAt(), now).toHours() : null));
        metrics.amountAdjustment("applied", adjustment.getApprovalMode().name());
        // a correção substitui a base de uma divergência que estivesse aberta
        divergences.findOpenByTitleId(title.getId()).ifPresent(d -> {
            d.close(AmountDivergenceStatus.SUPERSEDED, null, null, null, now);
            divergences.save(d);
        });
    }

    // =================================================================== divergência com a carga

    @Override
    public DivergenceView resolveDivergence(UUID divergenceId, String decision, String note) {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_ADJUST);
        TitleAmountDivergence divergence = divergences.findById(divergenceId)
                .orElseThrow(() -> ResourceNotFoundException.of("title_amount_divergence", divergenceId));
        DebtTitle title = lockForCommand(divergence.getTitleId());
        Guard.require("KEEP_MANUAL".equals(decision) || "ACCEPT_CREDITOR".equals(decision),
                "title_amount.divergence_decision_invalid", "Decisão deve ser KEEP_MANUAL ou ACCEPT_CREDITOR");
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        Money previousPrincipal = title.getPrincipalAmount();
        if ("KEEP_MANUAL".equals(decision)) {
            divergence.close(AmountDivergenceStatus.KEPT_MANUAL, actor.id(), actor.name(), note, now);
            title.acknowledgeCreditorPrincipal(divergence.getReportedPrincipal());
            titles.save(title);
        } else {
            divergence.close(AmountDivergenceStatus.ACCEPTED_CREDITOR, actor.id(), actor.name(), note, now);
            Guard.requireState(title.getStatus().acceptsPostings(), "title.not_accepting_postings",
                    "Título fora de cobrança não recebe o valor do credor");
            requireNoAgreement(title);
            title.dropManualPrincipal();
            Posting posting = TitleLedger.adjustPrincipal(title, ledgerRecorder.existingEntries(title),
                    divergence.getReportedPrincipal(), now, REF_DIVERGENCE, divergence.getId(),
                    "Valor do credor aceito na divergência");
            ledgerRecorder.record(title, posting, "debt_title.principal_adjusted", Trigger.IMPORT);
        }
        TitleAmountDivergence saved = divergences.save(divergence);
        auditTrail.record(AuditEntry.of("title_amount.divergence_resolved", TITLE, title.getId())
                .creditor(title.getCreditorId()).reason(saved.getResolutionNote())
                .change("principalAmount", previousPrincipal, title.getPrincipalAmount())
                .meta("divergenceId", saved.getId())
                .meta("decision", saved.getStatus())
                .meta("reportedPrincipal", saved.getReportedPrincipal()));
        businessEvents.log(divergenceEvent(BusinessEventTypes.TITLE_AMOUNT_DIVERGENCE_RESOLVED, title, saved)
                .attr("decision", saved.getStatus())
                .attr("daysOpen", Duration.between(saved.getDetectedAt(), now).toDays()));
        metrics.amountDivergence(saved.getStatus().name());
        return view(saved, title);
    }

    // =================================================================== evidência e limite

    @Override
    @Transactional(readOnly = true)
    public EvidenceDownload evidence(UUID adjustmentId) {
        TitleAmountAdjustment adjustment = load(adjustmentId);
        TitleAmountAdjustment.Evidence evidence = adjustment.evidence()
                .orElseThrow(() -> new ResourceNotFoundException("title_amount_evidence", adjustmentId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", adjustmentId.toString(),
                        "category", FileCategory.EVIDENCE.name())));
        return new EvidenceDownload(evidence.name(), evidence.contentType(), evidence.size(),
                fileStorage.open(evidence.key()));
    }

    @Override
    @Transactional(readOnly = true)
    public SettingsView settings() {
        if (!currentActor.get().has(Permission.TITLE_AMOUNT_APPROVE)) {
            accessScope.requirePermission(Permission.TITLE_AMOUNT_ADJUST);
        }
        return settings.findCurrent().map(this::settingsView)
                .orElse(new SettingsView(AmountAdjustmentSettings.DEFAULT_THRESHOLD.amount(), false, null, null, null,
                        null, null, null, false, cumulativeWindowDays()));
    }

    @Override
    public SettingsView updateSettings(BigDecimal approvalThreshold) {
        requireSettingsManager();
        Guard.require(approvalThreshold != null, "title_amount.threshold_invalid", "Informe o limite");
        Money value = Money.of(approvalThreshold);
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        Optional<AmountAdjustmentSettings> current = settings.findCurrent();
        Money previous = current.map(AmountAdjustmentSettings::getApprovalThreshold)
                .orElse(AmountAdjustmentSettings.DEFAULT_THRESHOLD);
        Money discarded = current.map(AmountAdjustmentSettings::getProposedThreshold).orElse(null);
        AmountAdjustmentSettings target = current.orElse(null);
        boolean applied;
        if (target == null) {
            target = new AmountAdjustmentSettings(value, actor.id(), actor.name(), now);
            applied = !target.hasProposal();
        } else {
            applied = target.change(value, actor.id(), actor.name(), now);
        }
        AmountAdjustmentSettings saved = settings.save(target);
        if (applied) {
            auditTrail.record(AuditEntry.of("title_amount.settings_updated", "amount_adjustment_settings", saved.getId())
                    .change("approvalThreshold", previous, saved.getApprovalThreshold())
                    .meta("discardedProposal", discarded));
        } else {
            // aumento: o limite vigente não muda até outra pessoa confirmar
            auditTrail.record(AuditEntry.of("title_amount.settings_proposed", "amount_adjustment_settings", saved.getId())
                    .change("proposedThreshold", discarded, saved.getProposedThreshold())
                    .meta("approvalThreshold", saved.getApprovalThreshold()));
        }
        return settingsView(saved);
    }

    @Override
    public SettingsView confirmSettings(BigDecimal reviewedThreshold) {
        requireSettingsManager();
        AmountAdjustmentSettings current = settings.findCurrent()
                .orElseThrow(() -> new ConflictException("title_amount.no_threshold_proposal",
                        "Não há aumento de limite aguardando confirmação", Map.of()));
        Money previous = current.getApprovalThreshold();
        UUID proposedBy = current.getProposedBy();
        current.confirmProposal(currentActor.get().id(), Money.ofNullable(reviewedThreshold), Instant.now(clock),
                proposalValidity());
        AmountAdjustmentSettings saved = settings.save(current);
        auditTrail.record(AuditEntry.of("title_amount.settings_confirmed", "amount_adjustment_settings", saved.getId())
                .change("approvalThreshold", previous, saved.getApprovalThreshold())
                .meta("proposedBy", proposedBy));
        return settingsView(saved);
    }

    /**
     * Descarta a proposta de aumento do limite vencida (job diário {@code debt.amount_threshold_proposal_expiry}, como ator
     * de sistema, na transação do job). O limite vigente não muda; a pendência some com a proposta.
     *
     * @return {@code true} se havia proposta vencida
     */
    public boolean discardExpiredProposal() {
        Optional<AmountAdjustmentSettings> current = settings.findCurrent();
        if (current.isEmpty()) {
            return false;
        }
        AmountAdjustmentSettings s = current.get();
        Money proposed = s.getProposedThreshold();
        UUID proposedBy = s.getProposedBy();
        Instant proposedAt = s.getProposedAt();
        if (!s.discardExpiredProposal(Instant.now(clock), proposalValidity())) {
            return false;
        }
        AmountAdjustmentSettings saved = settings.save(s);
        auditTrail.record(AuditEntry.of("title_amount.settings_proposal_expired", "amount_adjustment_settings",
                        saved.getId())
                .change("proposedThreshold", proposed, null)
                .meta("approvalThreshold", saved.getApprovalThreshold())
                .meta("proposedBy", proposedBy)
                .meta("proposedAt", proposedAt));
        metrics.thresholdProposalExpired();
        return true;
    }

    /** O limite vale para toda a assessoria: só quem aprova e vê todos os credores o altera ou confirma. */
    private void requireSettingsManager() {
        accessScope.requirePermission(Permission.TITLE_AMOUNT_APPROVE);
        accessScope.requireAllCreditors("amount_adjustment_settings");
    }

    private SettingsView settingsView(AmountAdjustmentSettings s) {
        return new SettingsView(s.getApprovalThreshold().amount(), true, s.getUpdatedAt(),
                s.getProposedThreshold() == null ? null : s.getProposedThreshold().amount(), s.getProposedBy(),
                s.getProposedByName(), s.getProposedAt(), s.proposalExpiresAt(proposalValidity()),
                s.isProposalExpired(Instant.now(clock), proposalValidity()), cumulativeWindowDays());
    }

    private Duration proposalValidity() {
        return properties.amountAdjustment().proposalValidity();
    }

    private long cumulativeWindowDays() {
        return properties.amountAdjustment().cumulativeWindow().toDays();
    }

    /** Reduções aplicadas sem segunda pessoa no título dentro da janela do limite acumulado. */
    private Money recentReductions(UUID titleId, Instant now) {
        return Money.of(adjustments.sumWithinLimitReductionsSince(titleId,
                now.minus(properties.amountAdjustment().cumulativeWindow())));
    }

    // =================================================================== auxiliares

    private void requireAdjustable(DebtTitle title) {
        Guard.requireState(title.getStatus().acceptsPostings(), "title_amount.status_invalid",
                "Só títulos em cobrança (em aberto ou suspensos) têm o valor corrigido");
        requireNoAgreement(title);
    }

    private void requireNoAgreement(DebtTitle title) {
        activeAgreement(title).ifPresent(agreementId -> {
            throw new ConflictException("title_amount.agreement_active",
                    "O título está num acordo em vigor: renegocie ou cancele o acordo antes de corrigir o valor",
                    Map.of("agreementId", agreementId.toString()));
        });
    }

    private Optional<UUID> activeAgreement(DebtTitle title) {
        TitleAgreementGuard guard = agreementGuard.getIfAvailable();
        return guard == null ? Optional.empty() : guard.activeAgreementCovering(title.getId());
    }

    private Money threshold() {
        return settings.findCurrent().map(AmountAdjustmentSettings::getApprovalThreshold)
                .orElse(AmountAdjustmentSettings.DEFAULT_THRESHOLD);
    }

    private static Map<LedgerComponent, Money> balances(List<TitleLedgerEntry> entries) {
        TitleBalance balance = TitleBalance.of(entries);
        Map<LedgerComponent, Money> out = new EnumMap<>(LedgerComponent.class);
        for (LedgerComponent c : LedgerComponent.values()) {
            out.put(c, balance.balanceOf(c));
        }
        return out;
    }

    /** Grava sem transação aberta; quem chama remove o arquivo ({@code EvidenceFileCleanup#discard}) se o registro falhar. */
    private TitleAmountAdjustment.Evidence storeOutsideTransaction(EvidenceUpload upload) {
        Guard.require(upload.content() != null && upload.size() > 0, "title_amount.evidence_empty",
                "Arquivo de evidência vazio");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "title_amount.evidence_too_large",
                "Arquivo de evidência excede o tamanho máximo");
        FileSignatures.Sniffed sniffed = FileSignatures.sniff(upload.content());
        String contentType = sniffed.contentType().orElseThrow(() -> new BusinessRuleException(
                "title_amount.evidence_type_invalid", "Evidência deve ser PDF, PNG ou JPEG (conferido pelo conteúdo)"));
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, sniffed.content(),
                upload.size());
        return new TitleAmountAdjustment.Evidence(stored.key(), stored.originalName(), contentType, stored.size(),
                stored.sha256());
    }

    private Set<UUID> creditorFilter() {
        Set<UUID> allowed = accessScope.restrictCreditors(Set.of());
        // JPQL não aceita lista vazia em "in": um id que não existe mantém a consulta válida e vazia
        return allowed.isEmpty() ? Set.of(new UUID(0L, 0L)) : allowed;
    }

    private TitleAmountAdjustment load(UUID adjustmentId) {
        TitleAmountAdjustment adjustment = adjustments.findById(adjustmentId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, adjustmentId));
        accessScope.requireVisible(RESOURCE, adjustmentId, adjustment.getCreditorId());
        return adjustment;
    }

    private DebtTitle loadVisible(UUID titleId) {
        DebtTitle title = titles.findById(titleId).orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
        accessScope.requireVisible(TITLE, titleId, title.getCreditorId());
        return title;
    }

    private DebtTitle lockForCommand(UUID titleId) {
        DebtTitle title = titles.findByIdForUpdate(titleId).orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
        accessScope.requireVisible(TITLE, titleId, title.getCreditorId());
        accessScope.requireCreditor(title.getCreditorId());
        return title;
    }

    private static BusinessEvent base(String type, DebtTitle title, TitleAmountAdjustment a) {
        return BusinessEvent.of(type)
                .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(title.getDebtorId())
                .reference("title", title.getId())
                .amount(a.getDelta())
                .attr("adjustmentId", a.getId())
                .attr("reason", a.getReasonCode())
                .attr("approvalMode", a.getApprovalMode())
                .attr("direction", a.getDelta().isNegative() ? "decrease" : "increase")
                .attr("principalChanged", a.changesPrincipal())
                .attr("withDispute", a.getDisputeId() != null)
                .attr("withEvidence", a.evidence().isPresent());
    }

    static BusinessEvent divergenceEvent(String type, DebtTitle title, TitleAmountDivergence d) {
        return BusinessEvent.of(type)
                .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(title.getDebtorId())
                .reference("title", title.getId())
                .amount(d.getReportedPrincipal().minus(d.getCurrentPrincipal()))
                .attr("divergenceId", d.getId())
                .attr("occurrences", d.getOccurrences());
    }

    private static AdjustmentView view(TitleAmountAdjustment a, DebtTitle title) {
        return new AdjustmentView(a.getId(), a.getTitleId(), a.getCreditorId(), a.getStatus().name(),
                a.getApprovalMode().name(), a.getReasonCode().name(), a.getReasonNote(), a.getDisputeId(),
                a.evidence().map(e -> new EvidenceInfo(e.name(), e.contentType(), e.size(), e.sha256())).orElse(null),
                a.getPreviousPrincipal().amount(), a.getNewPrincipal().amount(), a.getPreviousOpenBalance().amount(),
                a.getNewOpenBalance().amount(), a.getDelta().amount(),
                a.getLines().stream().map(l -> new AdjustmentLineView(l.getComponent().name(),
                        l.getPreviousBalance().amount(), l.getNewBalance().amount(),
                        l.getNewBalance().minus(l.getPreviousBalance()).amount())).toList(),
                a.getRequestedBy(), a.getRequestedByName(), a.getRequestedAt(), a.getDecidedBy(), a.getDecidedByName(),
                a.getDecidedAt(), a.getDecisionNote(), a.getAppliedAt(), label(title));
    }

    static DivergenceView view(TitleAmountDivergence d, DebtTitle title) {
        return new DivergenceView(d.getId(), d.getTitleId(), d.getCreditorId(), d.getStatus().name(),
                d.getCurrentPrincipal().amount(), d.getReportedPrincipal().amount(), d.getImportBatchId(),
                d.getDetectedAt(), d.getLastSeenAt(), d.getOccurrences(), d.getResolvedBy(), d.getResolvedByName(),
                d.getResolvedAt(), d.getResolutionNote(), label(title));
    }

    private static String label(DebtTitle title) {
        if (title == null) {
            return null;
        }
        return title.getInstallmentNumber() == null ? title.getExternalId()
                : title.getExternalId() + "/" + title.getInstallmentNumber();
    }
}
