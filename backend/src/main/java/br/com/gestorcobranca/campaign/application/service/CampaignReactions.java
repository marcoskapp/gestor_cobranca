package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.AudienceStore;
import br.com.gestorcobranca.campaign.domain.model.Attribution;
import br.com.gestorcobranca.campaign.domain.model.AttributionKind;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import br.com.gestorcobranca.campaign.domain.repository.AttributionRepository;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.StepExecutionRepository;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.campaign.domain.service.ReplyRouting;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reações aos eventos de integração, no tenant do evento, como ator de sistema (consumidores
 * idempotentes em {@link CampaignEventListeners}): atualização do read model, pausas e retomadas
 * automáticas, transições por resposta (RB07) e atribuição de resultados. Não chama casos de uso que
 * possam falhar por regra de outro módulo (a transação do consumo seria desfeita e o evento
 * repetido): ações no caso por resposta são executadas pelo job de etapa.
 */
@Component
class CampaignReactions {

    private static final Set<DenialReason> RESTRICTIONS = EnumSet.of(DenialReason.RESTRICTED_DEBTOR,
            DenialReason.RESTRICTED_CHANNEL, DenialReason.DEBTOR_ANONYMIZED);

    private final EnrollmentRepository enrollments;
    private final CampaignRepository campaigns;
    private final CadenceRepository cadences;
    private final StepExecutionRepository executions;
    private final AttributionRepository attributions;
    private final AudienceProjection projection;
    private final AudienceStore audience;
    private final EnrollmentLifecycle lifecycle;
    private final CampaignCodec codec;
    private final CampaignEvents events;
    private final CampaignMetrics metrics;
    private final ContactGate gate;
    private final Clock clock;

    CampaignReactions(EnrollmentRepository enrollments, CampaignRepository campaigns, CadenceRepository cadences,
                      StepExecutionRepository executions, AttributionRepository attributions,
                      AudienceProjection projection, AudienceStore audience, EnrollmentLifecycle lifecycle,
                      CampaignCodec codec, CampaignEvents events, CampaignMetrics metrics, ContactGate gate, Clock clock) {
        this.enrollments = enrollments;
        this.campaigns = campaigns;
        this.cadences = cadences;
        this.executions = executions;
        this.attributions = attributions;
        this.projection = projection;
        this.audience = audience;
        this.lifecycle = lifecycle;
        this.codec = codec;
        this.events = events;
        this.metrics = metrics;
        this.gate = gate;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ caso

    /**
     * Posição do caso mudou (status, saldo, elegibilidade, contestação, dono, promessa, acordo,
     * jurídico): atualiza a projeção e reavalia as inscrições em andamento do caso — encerra se o caso
     * fechou, pausa se a situação bloqueia a régua, retoma pausas cuja causa cessou.
     */
    void caseChanged(UUID caseId, String trigger) {
        if (caseId == null) {
            return;
        }
        AudienceSnapshot snapshot = projection.refresh(caseId, trigger);
        Map<UUID, Campaign> cache = new HashMap<>();
        for (Enrollment e : enrollments.findRunningByCaseForUpdate(caseId)) {
            Campaign c = cache.computeIfAbsent(e.getCampaignId(), id -> campaigns.findById(id).orElseThrow());
            if (!snapshot.active()) {
                lifecycle.end(e, c, EndReason.CASE_CLOSED);
                continue;
            }
            Optional<PauseReason> blocking = PauseRules.blocking(snapshot, c.getPurpose());
            if (e.getStatus() != EnrollmentStatus.PAUSED) {
                blocking.ifPresent(reason -> lifecycle.pause(e, c, reason, null, trigger));
            } else {
                tryResume(e, c, snapshot, trigger);
            }
        }
    }

    /** Retomada automática: causa cessou na posição do caso e, para restrição, algum canal da régua está livre. */
    boolean tryResume(Enrollment e, Campaign c, AudienceSnapshot snapshot, String trigger) {
        if (e.getStatus() != EnrollmentStatus.PAUSED || !PauseRules.cleared(e.getPauseReason(), snapshot, c.getPurpose())) {
            return false;
        }
        if (e.getPauseReason() == PauseReason.CONTACT_RESTRICTED && !anyChannelUnrestricted(e)) {
            return false;
        }
        lifecycle.resume(e, trigger);
        return true;
    }

    private boolean anyChannelUnrestricted(Enrollment e) {
        Set<String> channels = definition(e).messageChannels();
        if (channels.isEmpty()) {
            return true;
        }
        Instant now = Instant.now(clock);
        return channels.stream().anyMatch(channel -> gate.evaluate(ContactRequest.of(e.getDebtorId(), e.getCreditorId(),
                channel, null, now, "CAMPAIGN")).reasons().stream().noneMatch(RESTRICTIONS::contains));
    }

    // ------------------------------------------------------------------ pagamento e acordo

    void paymentConfirmed(PaymentConfirmed event) {
        Instant at = event.paidAt() != null ? event.paidAt() : event.occurredAt();
        attribute(event.debtorId(), event.creditorId(), at, AttributionKind.PAYMENT, "payment", event.paymentId(),
                Money.of(event.amount()));
        Instant now = Instant.now(clock);
        for (Enrollment e : enrollments.findRunningByDebtorForUpdate(event.debtorId())) {
            if (!e.getCreditorId().equals(event.creditorId())
                    || (event.portfolioId() != null && !event.portfolioId().equals(e.getPortfolioId()))) {
                continue;
            }
            Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
            if (!PauseRules.pausesOnPayment(c.getPurpose())) {
                continue;
            }
            Instant resumeAt = c.getPaymentPauseDays() == null ? null : now.plus(Duration.ofDays(c.getPaymentPauseDays()));
            if (e.getStatus() == EnrollmentStatus.PAUSED) {
                continue; // já pausada por outra causa: a retomada exige que ela cesse
            }
            lifecycle.pause(e, c, PauseReason.PAYMENT_CONFIRMED, resumeAt, "payment_confirmed");
        }
    }

    void paymentReversed(PaymentReversed event) {
        for (UUID enrollmentId : attributions.enrollmentIdsFor(AttributionKind.PAYMENT, event.paymentId())) {
            Enrollment e = enrollments.findById(enrollmentId).orElseThrow();
            record(e, AttributionKind.PAYMENT_REVERSAL, "payment_reversal", event.reversalId(),
                    Money.of(event.amount() == null ? BigDecimal.ZERO : event.amount()).negate(), event.occurredAt());
        }
    }

    void agreementStatusChanged(AgreementStatusChanged event) {
        if ("FORMALIZED".equals(event.newStatus()) && event.previousStatus() == null) {
            attribute(event.debtorId(), event.creditorId(), event.occurredAt(), AttributionKind.AGREEMENT, "agreement",
                    event.agreementId(), event.totalAmount() == null ? null : Money.of(event.totalAmount()));
        }
        if ("BROKEN".equals(event.newStatus()) && event.caseId() != null) {
            audience.incrementBrokenAgreements(event.caseId());
        }
        caseChanged(event.caseId(), "agreement");
    }

    // ------------------------------------------------------------------ restrição de contato

    void contactRestrictionChanged(ContactRestrictionChanged event) {
        if (event.debtorId() == null) {
            return;
        }
        for (Enrollment e : enrollments.findRunningByDebtorForUpdate(event.debtorId())) {
            if (event.creditorId() != null && !event.creditorId().equals(e.getCreditorId())) {
                continue;
            }
            Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
            if (event.restricted()) {
                Set<String> channels = definition(e).messageChannels();
                boolean allChannels = event.channel() == null || (!channels.isEmpty() && Set.of(event.channel())
                        .containsAll(channels));
                if (allChannels && e.getStatus() != EnrollmentStatus.PAUSED) {
                    lifecycle.pause(e, c, PauseReason.CONTACT_RESTRICTED, null, "contact_restriction");
                }
            } else if (e.getStatus() == EnrollmentStatus.PAUSED && e.getPauseReason() == PauseReason.CONTACT_RESTRICTED) {
                tryResume(e, c, projection.refresh(e.getCaseId(), "contact_restriction"), "contact_restriction");
            }
        }
    }

    // ------------------------------------------------------------------ interações e mensagens

    void effectiveContact(UUID debtorId, UUID creditorId, UUID interactionId, Instant at) {
        for (Enrollment observing : enrollments.findObservingByDebtorAndCreditor(debtorId, creditorId, at)) {
            Enrollment e = enrollments.findByIdForUpdate(observing.getId()).orElseThrow();
            if (record(e, AttributionKind.EFFECTIVE_CONTACT, "interaction", interactionId, null, at)) {
                e.registerEffectiveContact(at);
                enrollments.save(e);
            }
        }
    }

    void messageStatusChanged(MessageStatusChanged event) {
        if ("OUTBOUND".equals(event.direction())) {
            if (event.campaignExecutionId() != null) {
                executions.findByMessageId(event.messageId()).ifPresent(s -> {
                    if (s.updateMessageStatus(event.status())) {
                        executions.save(s);
                    }
                });
            }
            return;
        }
        if (!"RECEIVED".equals(event.status()) || event.debtorId() == null || event.creditorId() == null) {
            return;
        }
        Instant at = event.occurredAt();
        for (Enrollment observing : enrollments.findObservingByDebtorAndCreditor(event.debtorId(), event.creditorId(), at)) {
            Enrollment e = enrollments.findByIdForUpdate(observing.getId()).orElseThrow();
            if (!record(e, AttributionKind.REPLY, "message", event.messageId(), null, at)) {
                continue;
            }
            e.registerReply(at);
            enrollments.save(e);
            if (e.getStatus() == EnrollmentStatus.ACTIVE) {
                redirect(e, event.channel());
            }
        }
    }

    /** RB07: a resposta redireciona a sequência pela regra da última etapa executada (ou a padrão da régua). */
    private void redirect(Enrollment e, String channel) {
        Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
        CadenceDefinition definition = definition(e);
        ReplyRouting.Route route = ReplyRouting.route(definition, e.getLastStepCode());
        Instant now = Instant.now(clock);
        switch (route.transition()) {
            case STOP -> lifecycle.end(e, c, EndReason.REPLIED);
            case GOTO_STEP -> {
                CadenceStep target = definition.step(route.targetStepCode()).orElseThrow();
                lifecycle.cancelScheduled(e);
                e.scheduleNext(target.code(), e.nextIteration(), now.plus(Duration.ofMinutes(target.waitMinutes())));
                enrollments.save(e);
                lifecycle.schedule(e);
            }
            case HAND_TO_ASSISTANT, MOVE_TO_QUEUE -> {
                lifecycle.cancelScheduled(e);
                e.requestReplyAction(route.transition(), now);
                enrollments.save(e);
                lifecycle.schedule(e);
            }
            case CONTINUE -> {
                // a régua segue normalmente
            }
        }
        metrics.reply(route.transition().name());
        events.replied(e, c.getCode(), route.transition().name(), channel);
    }

    // ------------------------------------------------------------------ atribuição

    private void attribute(UUID debtorId, UUID creditorId, Instant at, AttributionKind kind, String referenceType,
                           UUID referenceId, Money amount) {
        if (debtorId == null || creditorId == null || referenceId == null) {
            return;
        }
        for (Enrollment e : enrollments.findObservingByDebtorAndCreditor(debtorId, creditorId, at)) {
            record(e, kind, referenceType, referenceId, amount, at);
        }
    }

    /** @return {@code true} se a atribuição é nova */
    private boolean record(Enrollment e, AttributionKind kind, String referenceType, UUID referenceId, Money amount,
                           Instant at) {
        if (attributions.exists(e.getId(), kind, referenceId)) {
            return false;
        }
        attributions.save(new Attribution(e, kind, referenceType, referenceId, amount, at));
        metrics.attribution(kind.name());
        return true;
    }

    private CadenceDefinition definition(Enrollment e) {
        return codec.cadence(cadences.findById(e.getCadenceId()).orElseThrow().getDefinition());
    }
}
