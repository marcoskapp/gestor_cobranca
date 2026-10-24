package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.FactStore;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.CaseFact;
import br.com.gestorcobranca.scoring.domain.model.FactKind;
import br.com.gestorcobranca.scoring.domain.repository.CaseScoreRepository;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatos do relacionamento de cobrança a partir dos eventos de integração (na transação do consumo
 * idempotente). Só ids, datas, valores, canal e situação. Sem base legal registrada na assessoria
 * nada é coletado (o score é uma finalidade própria do tratamento). Fatos que mudam a propensão
 * agendam o recálculo do caso (agrupado por {@code app.scoring.rescore-delay}).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class FactRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(FactRecorder.class);

    private final FactStore facts;
    private final SnapshotStore snapshots;
    private final CaseScoreRepository scores;
    private final ScoringSettingsProvider settings;
    private final CaseUseCases cases;
    private final DebtTitleUseCases titles;
    private final JobQueue jobQueue;
    private final ScoringMetrics metrics;
    private final ScoringProperties properties;
    private final Clock clock;

    FactRecorder(FactStore facts, SnapshotStore snapshots, CaseScoreRepository scores, ScoringSettingsProvider settings,
                 CaseUseCases cases, DebtTitleUseCases titles, JobQueue jobQueue, ScoringMetrics metrics,
                 ScoringProperties properties, Clock clock) {
        this.facts = facts;
        this.snapshots = snapshots;
        this.scores = scores;
        this.settings = settings;
        this.cases = cases;
        this.titles = titles;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    void interaction(InteractionRecorded e) {
        if (!enabled("interaction")) {
            return;
        }
        boolean any = false;
        if ("OUTBOUND".equals(e.direction())) {
            any |= record(e.eventId(), fact(FactKind.CONTACT_ATTEMPT, null, e.caseId(), e.debtorId(), e.creditorId(),
                    e.portfolioId(), e.interactionId(), e.channel(), e.occurredAt()));
        } else if ("INBOUND".equals(e.direction())) {
            any |= record(e.eventId(), fact(FactKind.DEBTOR_RESPONSE, null, e.caseId(), e.debtorId(), e.creditorId(),
                    e.portfolioId(), e.interactionId(), e.channel(), e.occurredAt()));
        }
        if (e.effectiveContact()) {
            any |= record(e.eventId(), fact(FactKind.EFFECTIVE_CONTACT, null, e.caseId(), e.debtorId(), e.creditorId(),
                    e.portfolioId(), e.interactionId(), e.channel(), e.occurredAt()));
            rescore(e.caseId());
        }
        if (!any) {
            metrics.factIgnored("interaction", e.direction() == null ? "not_applicable" : "duplicate");
        }
    }

    void message(MessageStatusChanged e) {
        if (!"INBOUND".equals(e.direction()) || !"RECEIVED".equals(e.status()) || e.debtorId() == null) {
            return;
        }
        if (!enabled("message")) {
            return;
        }
        record(e.eventId(), fact(FactKind.DEBTOR_RESPONSE, e.status(), e.caseId(), e.debtorId(), e.creditorId(),
                e.portfolioId(), e.messageId(), e.channel(), e.occurredAt()));
    }

    void call(CallCompleted e) {
        if (!CallCompleted.ANSWERED.equals(e.result()) || e.debtorId() == null || !enabled("call")) {
            return;
        }
        record(e.eventId(), fact(FactKind.DEBTOR_RESPONSE, e.result(), e.caseId(), e.debtorId(), e.creditorId(),
                e.portfolioId(), e.callId(), "PHONE", e.occurredAt()));
    }

    void promise(PromiseStatusChanged e) {
        if (!enabled("promise")) {
            return;
        }
        CaseFact f = new CaseFact(FactKind.PROMISE, e.status(), e.caseId(), e.debtorId(), e.creditorId(), e.portfolioId(),
                e.promiseId(), null, e.amount(), e.occurredAt(), e.occurredAt());
        if (record(e.eventId(), f)) {
            rescore(e.caseId());
        }
    }

    void agreement(AgreementStatusChanged e) {
        if (!enabled("agreement")) {
            return;
        }
        CaseFact f = new CaseFact(FactKind.AGREEMENT, e.newStatus(), e.caseId(), e.debtorId(), e.creditorId(),
                e.portfolioId(), e.agreementId(), null, e.totalAmount(), e.occurredAt(), e.occurredAt());
        if (record(e.eventId(), f)) {
            rescore(e.caseId() != null ? e.caseId() : activeCase(e.debtorId(), e.portfolioId()));
        }
    }

    void payment(PaymentConfirmed e) {
        if (!enabled("payment")) {
            return;
        }
        UUID portfolioId = e.portfolioId() != null ? e.portfolioId() : portfolioOfAllocations(e);
        if (portfolioId == null) {
            metrics.factIgnored("payment", "no_portfolio");
            LOG.atInfo().addKeyValue("paymentId", e.paymentId())
                    .log("Pagamento sem carteira identificável: fora das features e do desfecho do score");
            return;
        }
        Instant paidAt = e.paidAt() != null ? e.paidAt() : e.occurredAt();
        CaseFact f = new CaseFact(FactKind.PAYMENT, e.method(), null, e.debtorId(), e.creditorId(), portfolioId,
                e.paymentId(), null, e.amount(), e.occurredAt(), paidAt);
        if (record(e.eventId(), f)) {
            rescore(activeCase(e.debtorId(), portfolioId));
        }
    }

    void reversal(PaymentReversed e) {
        if (!enabled("payment_reversal")) {
            return;
        }
        CaseFact f = new CaseFact(FactKind.PAYMENT_REVERSAL, e.reason(), null, e.debtorId(), e.creditorId(),
                e.portfolioId(), e.paymentId(), null, e.amount(), e.occurredAt(), e.occurredAt());
        if (record(e.eventId(), f) && e.portfolioId() != null) {
            rescore(activeCase(e.debtorId(), e.portfolioId()));
        }
    }

    /** LGPD: apaga fatos e scores vigentes do devedor; snapshots apurados ficam sem o devedor (estatística). */
    void debtorAnonymized(DebtorAnonymized e) {
        int removedFacts = facts.deleteByDebtor(e.debtorId());
        long removedScores = scores.deleteByDebtorId(e.debtorId());
        int snapshotRows = snapshots.anonymizeDebtor(e.debtorId());
        LOG.atInfo().addKeyValue("facts", removedFacts).addKeyValue("scores", removedScores)
                .addKeyValue("snapshots", snapshotRows).log("Dados do score neutralizados na anonimização do devedor");
    }

    private boolean enabled(String type) {
        if (settings.enabled()) {
            return true;
        }
        metrics.factIgnored(type, "disabled");
        return false;
    }

    private boolean record(UUID eventId, CaseFact f) {
        boolean recorded = facts.record(eventId, f);
        if (recorded) {
            metrics.factRecorded(f.kind().name());
        }
        return recorded;
    }

    private static CaseFact fact(FactKind kind, String status, UUID caseId, UUID debtorId, UUID creditorId,
                                 UUID portfolioId, UUID referenceId, String channel, Instant at) {
        return new CaseFact(kind, status, caseId, debtorId, creditorId, portfolioId, referenceId, channel, null, at, at);
    }

    private UUID portfolioOfAllocations(PaymentConfirmed e) {
        return e.allocations().stream().map(PaymentConfirmed.Allocation::titleId).filter(java.util.Objects::nonNull)
                .findFirst().map(t -> titles.get(t).portfolioId()).orElse(null);
    }

    private UUID activeCase(UUID debtorId, UUID portfolioId) {
        if (debtorId == null || portfolioId == null) {
            return null;
        }
        Optional<CaseUseCases.CaseView> found = cases.findActive(debtorId, portfolioId);
        return found.map(CaseUseCases.CaseView::id).orElse(null);
    }

    private void rescore(UUID caseId) {
        if (caseId == null) {
            return;
        }
        jobQueue.enqueue(ScoringJobHandlers.RescoreJob.TYPE, new ScoringJobHandlers.RescoreJob.Payload(caseId),
                Instant.now(clock).plus(properties.rescoreDelay()), "case:" + caseId);
    }
}
