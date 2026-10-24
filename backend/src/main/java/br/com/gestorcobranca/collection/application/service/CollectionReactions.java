package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.shared.events.compliance.ContactPolicyPublished;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.organization.PortfolioStatusChanged;
import br.com.gestorcobranca.shared.events.scoring.CaseScoreChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reações do módulo aos eventos de integração de outros módulos, executadas pelos consumidores
 * dentro da transação de consumo idempotente (ator de sistema do tenant do evento).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CollectionReactions {

    private static final Set<String> AGREEMENT_ACTIVE = Set.of("FORMALIZED", "IN_PAYMENT");
    private static final Set<String> AGREEMENT_ENDED = Set.of("BROKEN", "CANCELLED");

    private final CollectionCaseRepository cases;
    private final CaseSynchronizer synchronizer;
    private final CaseRecorder recorder;
    private final CaseAgenda agenda;
    private final PromiseService promises;
    private final PortfolioSyncJob portfolioSync;
    private final CaseSweepJob sweep;
    private final CollectionSettingsUseCases settings;
    private final CollectionMetrics metrics;
    private final CaseAccess access;
    private final Clock clock;

    CollectionReactions(CollectionCaseRepository cases, CaseSynchronizer synchronizer, CaseRecorder recorder,
                        CaseAgenda agenda, PromiseService promises, PortfolioSyncJob portfolioSync, CaseSweepJob sweep,
                        CollectionSettingsUseCases settings, CollectionMetrics metrics, CaseAccess access, Clock clock) {
        this.cases = cases;
        this.synchronizer = synchronizer;
        this.recorder = recorder;
        this.agenda = agenda;
        this.promises = promises;
        this.portfolioSync = portfolioSync;
        this.sweep = sweep;
        this.settings = settings;
        this.metrics = metrics;
        this.access = access;
        this.clock = clock;
    }

    /** Título mudou (status, saldo, contestação, carteira): abre, atualiza ou encerra os casos do devedor no credor. */
    void titleStatusChanged(TitleStatusChanged e) {
        metrics.eventConsumed("title_status");
        synchronizer.syncDebtor(e.debtorId(), e.creditorId(), "title_status", synchronizer.context());
    }

    /** Carga concluída: sincroniza os devedores do lote em segundo plano (lotes de devedores). */
    void portfolioLoaded(PortfolioLoaded e) {
        metrics.eventConsumed("portfolio_loaded");
        portfolioSync.enqueue(e.creditorId(), e.portfolioId(), e.importBatchId());
    }

    /** Carteira ativada/suspensa/devolvida/encerrada: a elegibilidade dos casos da carteira muda. */
    void portfolioStatusChanged(PortfolioStatusChanged e) {
        metrics.eventConsumed("portfolio_status");
        sweep.enqueue(e.creditorId(), e.portfolioId(), "portfolio_status", null);
    }

    /** Restrição criada/revogada/expirada: reavalia os casos ativos do devedor (no credor, se restrito a um). */
    void contactRestrictionChanged(ContactRestrictionChanged e) {
        metrics.eventConsumed("contact_restriction");
        if (e.debtorId() == null) {
            // restrição de um valor de contato sem devedor identificado: não afeta a elegibilidade por canal
            return;
        }
        access.lockDebtor(e.debtorId());
        List<UUID> ids = cases.findActiveIdsByDebtor(e.debtorId(), e.creditorId());
        CaseSynchronizer.Context ctx = synchronizer.context();
        cases.findAllByIdIn(ids).forEach(c -> synchronizer.reevaluate(c, "contact_restriction", ctx));
    }

    /** Nova política de contato: reavalia os casos do credor (ou de todos) a partir do início da vigência. */
    void contactPolicyPublished(ContactPolicyPublished e) {
        metrics.eventConsumed("contact_policy");
        Instant now = Instant.now(clock);
        Instant runAt = e.effectiveFrom() != null && e.effectiveFrom().isAfter(now) ? e.effectiveFrom() : null;
        sweep.enqueue(e.creditorId(), null, "contact_policy", runAt);
    }

    /**
     * Acordo formalizado/em pagamento → estágio {@code AGREEMENT} (a promessa pendente é substituída);
     * quebrado/cancelado → volta a {@code IN_NEGOTIATION}. Quitação encerra o caso pelos títulos.
     */
    void agreementStatusChanged(AgreementStatusChanged e) {
        metrics.eventConsumed("agreement_status");
        access.lockDebtor(e.debtorId());
        Optional<CollectionCase> found = e.caseId() != null ? cases.findById(e.caseId())
                : e.portfolioId() == null ? Optional.empty()
                : cases.findActiveByDebtorAndPortfolio(e.debtorId(), e.portfolioId());
        if (found.isEmpty() || !found.get().getStatus().isActive()) {
            return;
        }
        CollectionCase c = found.get();
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        if (AGREEMENT_ACTIVE.contains(e.newStatus())) {
            promises.cancelPending(c.getId(), "Substituída por acordo formalizado");
            c.moveToStage(CaseStatus.AGREEMENT, "Acordo " + e.newStatus());
        } else if (AGREEMENT_ENDED.contains(e.newStatus()) && stage(c) == CaseStatus.AGREEMENT) {
            c.moveToStage(CaseStatus.IN_NEGOTIATION, "Acordo " + e.newStatus());
        } else {
            return;
        }
        agenda.refresh(c);
        recorder.commit(c, before, AssignmentMode.ROUTING, null, "agreement_status");
    }

    /**
     * Score de propensão recalculado (módulo scoring): o caso guarda o score para a ordem
     * {@code HIGHEST_SCORE} do "próximo caso" e o critério de faixa de score das filas. Só o cálculo
     * mais recente prevalece (instante do cálculo); caso encerrado é ignorado.
     */
    void caseScoreChanged(CaseScoreChanged e) {
        metrics.eventConsumed("case_score");
        Optional<UUID> debtorId = cases.findDebtorIdById(e.caseId());
        if (debtorId.isEmpty()) {
            return;
        }
        access.lockDebtor(debtorId.get());
        cases.findById(e.caseId()).ifPresent(c -> synchronizer.applyScore(c, e.score(), e.calculatedAt(), "case_score"));
    }

    void tenantProvisioned() {
        metrics.eventConsumed("tenant_provisioned");
        settings.ensureDefaults();
    }

    private static CaseStatus stage(CollectionCase c) {
        return c.getStatus().isHold() ? c.getResumeStatus() : c.getStatus();
    }
}
