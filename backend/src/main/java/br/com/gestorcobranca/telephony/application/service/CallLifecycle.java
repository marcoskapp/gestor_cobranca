package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallResult;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.model.HangupSide;
import br.com.gestorcobranca.telephony.domain.model.InteractionState;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerEntryRepository;
import br.com.gestorcobranca.telephony.domain.service.CallTabulations;
import br.com.gestorcobranca.telephony.domain.service.RetryPolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Efeitos de negócio do ciclo de vida da chamada, na transação da mudança de estado:
 * <ul>
 *   <li>discagem aceita → {@code call.dialed};</li>
 *   <li>negação do porteiro → {@code call.blocked} e métrica por motivo;</li>
 *   <li>encerramento → métricas (resultado, toque, conversação, chamadas curtas),
 *       {@code CallCompleted} (incentive: TMA e ocupação), {@code call.completed}; operador em
 *       pós-atendimento (prazo da tabulação) ou livre; tabulação automática e registro no caso
 *       (job) quando não há operador; retentativa do item da campanha e novo ciclo do discador.</li>
 * </ul>
 */
@Component
class CallLifecycle {

    private static final Duration SHORT_CALL = Duration.ofSeconds(3);

    private final CallRepository calls;
    private final AgentRepository agents;
    private final DialerEntryRepository entries;
    private final DialerCampaignRepository campaigns;
    private final PresenceRecorder presence;
    private final DialerTicks ticks;
    private final JobQueue jobQueue;
    private final ApplicationEventPublisher publisher;
    private final BusinessEventLogger businessEvents;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final CurrentActor currentActor;
    private final Clock clock;

    CallLifecycle(CallRepository calls, AgentRepository agents, DialerEntryRepository entries,
                  DialerCampaignRepository campaigns, PresenceRecorder presence, DialerTicks ticks, JobQueue jobQueue,
                  ApplicationEventPublisher publisher, BusinessEventLogger businessEvents, TelephonyMetrics metrics,
                  TelephonyProperties properties, CurrentActor currentActor, Clock clock) {
        this.calls = calls;
        this.agents = agents;
        this.entries = entries;
        this.campaigns = campaigns;
        this.presence = presence;
        this.ticks = ticks;
        this.jobQueue = jobQueue;
        this.publisher = publisher;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    void dialed(Call call) {
        metrics.dial(call.getOrigin().name(), "accepted");
        businessEvents.log(base(BusinessEventTypes.CALL_DIALED, call)
                .attr("attemptNumber", call.getAttemptNumber())
                .attr("recording", call.isRecordingRequested()));
    }

    /** @param stage {@code precheck} (clique para ligar) ou {@code dial} (imediatamente antes da discagem) */
    void blocked(Call call, List<String> reasons, Instant nextAllowedAt, String stage) {
        metrics.dial(call.getOrigin().name(), "blocked");
        reasons.forEach(r -> metrics.blocked(call.getOrigin().name(), stage, r));
        businessEvents.log(base(BusinessEventTypes.CALL_BLOCKED, call)
                .attr("stage", stage)
                .attr("reasons", String.join(",", reasons))
                .attr("deferrable", nextAllowedAt != null)
                .attr("attemptNumber", call.getAttemptNumber()));
        releaseOperator(call);
    }

    /** Chamada chegou a um estado terminal (já salvo pelo chamador ou a salvar em seguida). */
    void ended(Call call) {
        Instant now = Instant.now(clock);
        String origin = call.getOrigin().name();
        if (call.wasDialed()) {
            metrics.callEnded(call.getDirection().name(), origin, call.getResult().name());
            metrics.ringTime(origin, call.getRingSeconds());
            if (call.getConnectedAt() != null) {
                metrics.talkTime(origin, call.getTalkSeconds());
            }
            if (call.getAnsweredAt() != null && call.getHangupSide() == HangupSide.SYSTEM
                    && !Duration.between(call.getAnsweredAt(), call.getEndedAt()).minus(SHORT_CALL).isPositive()) {
                metrics.shortCall(origin);
            }
            publisher.publishEvent(new CallCompleted(Ids.newId(), currentActor.tenantId(), call.getEndedAt(), call.getId(),
                    call.getCaseId(), call.getDebtorId(), call.getCreditorId(), call.getOperatorId(),
                    call.getDirection().name(), call.getResult().name(), call.getTalkSeconds(), call.getPortfolioId()));
            BusinessEvent event = base(BusinessEventTypes.CALL_COMPLETED, call)
                    .attr("status", call.getStatus())
                    .attr("result", call.getResult())
                    .attr("attemptNumber", call.getAttemptNumber())
                    .attr("amd", call.getAmdResult())
                    .attr("abandoned", call.isAbandoned())
                    .attr("hangupSide", call.getHangupSide())
                    .attr("ringSeconds", call.getRingSeconds())
                    .attr("talkSeconds", call.getTalkSeconds())
                    .attr("failureCode", call.getFailureCode())
                    .attr("recording", call.isRecordingRequested());
            businessEvents.log(event.durationMs(call.getTalkSeconds() * 1000L));
            if (call.getResult() == CallResult.ABANDONED) {
                businessEvents.log(base(BusinessEventTypes.CALL_ABANDONED, call)
                        .attr("attemptNumber", call.getAttemptNumber())
                        .attr("amd", call.getAmdResult()));
            }
        }
        if (call.getInteractionState() == InteractionState.AWAITING_TABULATION) {
            jobQueue.enqueue(TabulationDeadlineJob.TYPE, new TabulationDeadlineJob.Payload(call.getId()),
                    call.getEndedAt().plus(properties.tabulationDeadline()), TabulationDeadlineJob.key(call.getId()));
        } else if (call.getInteractionState() == InteractionState.PENDING) {
            call.autoTabulate(CallTabulations.automatic(call.getResult()), now);
            metrics.tabulation("automatic");
            jobQueue.enqueue(RecordInteractionJob.TYPE, new RecordInteractionJob.Payload(call.getId()), null,
                    RecordInteractionJob.key(call.getId()));
        }
        calls.save(call);
        if (call.getOperatorId() != null) {
            boolean awaiting = call.getInteractionState() == InteractionState.AWAITING_TABULATION;
            agents.findByUserIdForUpdate(call.getOperatorId()).ifPresent(agent -> {
                presence.record(agent.callEnded(call.getId(), awaiting, now), "call");
                agents.save(agent);
            });
        }
        if (call.getEntryId() != null) {
            finishEntry(call, now);
        }
    }

    /** Operador reservado para a chamada cuja conexão falhou volta a disponível. */
    void releaseAfterFailedBridge(br.com.gestorcobranca.telephony.domain.model.Agent agent, java.util.UUID callId) {
        presence.record(agent.callEnded(callId, false, Instant.now(clock)), "call");
        agents.save(agent);
    }

    /** Operador reservado para uma chamada que não chegou a discar volta a disponível. */
    private void releaseOperator(Call call) {
        if (call.getOperatorId() == null) {
            return;
        }
        agents.findByUserIdForUpdate(call.getOperatorId()).ifPresent(agent -> {
            presence.record(agent.callEnded(call.getId(), false, Instant.now(clock)), "call");
            agents.save(agent);
        });
    }

    private void finishEntry(Call call, Instant now) {
        Optional<DialerEntry> entry = entries.findById(call.getEntryId());
        Optional<DialerCampaign> campaign = campaigns.findById(call.getCampaignId());
        if (entry.isEmpty() || campaign.isEmpty()) {
            return;
        }
        RetryPolicy.Decision decision = RetryPolicy.after(call.getResult(), entry.get().getAttempts(),
                campaign.get().getMaxAttempts(), Duration.ofMinutes(campaign.get().getRetryIntervalMinutes()), now);
        if (entry.get().attemptFinished(call.getId(), call.getResult(), decision.status(), decision.nextAttemptAt(), now)) {
            entries.save(entry.get());
            metrics.entry(decision.status() == br.com.gestorcobranca.telephony.domain.model.DialerEntryStatus.PENDING
                    ? "retry" : decision.status().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (campaign.get().isActive()) {
            ticks.requestNow(campaign.get().getId());
        }
    }

    private BusinessEvent base(String type, Call call) {
        return BusinessEvent.of(type)
                .creditor(call.getCreditorId())
                .portfolio(call.getPortfolioId())
                .debtor(call.getDebtorId())
                .collectionCase(call.getCaseId())
                .reference("call", call.getId())
                .channel("PHONE")
                .attr("direction", call.getDirection())
                .attr("origin", call.getOrigin())
                .attr("operatorId", call.getOperatorId())
                .attr("campaignId", call.getCampaignId());
    }
}
