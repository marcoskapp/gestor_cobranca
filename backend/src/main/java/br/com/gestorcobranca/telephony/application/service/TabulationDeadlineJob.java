package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.InteractionState;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.service.CallTabulations;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Prazo do pós-atendimento vencido sem tabulação do operador ({@code telephony.tabulation_deadline}):
 * tabula com a sugestão do resultado (atendida → {@code CALL_ANSWERED}), registra no caso como
 * sistema e libera o operador. Cancelado quando o operador tabula.
 */
@Component
class TabulationDeadlineJob implements JobHandler<TabulationDeadlineJob.Payload> {

    static final String TYPE = "telephony.tabulation_deadline";

    private final CallRepository calls;
    private final AgentRepository agents;
    private final PresenceRecorder presence;
    private final JobQueue jobQueue;
    private final BusinessEventLogger businessEvents;
    private final TelephonyMetrics metrics;
    private final Clock clock;

    TabulationDeadlineJob(CallRepository calls, AgentRepository agents, PresenceRecorder presence, JobQueue jobQueue,
                          BusinessEventLogger businessEvents, TelephonyMetrics metrics, Clock clock) {
        this.calls = calls;
        this.agents = agents;
        this.presence = presence;
        this.jobQueue = jobQueue;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    record Payload(UUID callId) {
    }

    static String key(UUID callId) {
        return "tabulation:" + callId;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<Call> found = calls.findByIdForUpdate(payload.callId());
        if (found.isEmpty() || found.get().getInteractionState() != InteractionState.AWAITING_TABULATION) {
            return;
        }
        Call call = found.get();
        Instant now = Instant.now(clock);
        call.autoTabulate(CallTabulations.automatic(call.getResult()), now);
        calls.save(call);
        metrics.tabulation("deadline");
        if (call.getInteractionState() == InteractionState.PENDING) {
            jobQueue.enqueue(RecordInteractionJob.TYPE, new RecordInteractionJob.Payload(call.getId()), null,
                    RecordInteractionJob.key(call.getId()));
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CALL_TABULATED)
                .creditor(call.getCreditorId()).portfolio(call.getPortfolioId()).debtor(call.getDebtorId())
                .collectionCase(call.getCaseId()).reference("call", call.getId()).channel("PHONE")
                .attr("mode", "deadline").attr("tabulationCode", call.getTabulationCode())
                .attr("result", call.getResult()).attr("operatorId", call.getOperatorId())
                .attr("wrapUpSeconds", call.getWrapUpSeconds()));
        if (call.getOperatorId() != null) {
            agents.findByUserIdForUpdate(call.getOperatorId()).ifPresent(agent -> {
                presence.record(agent.wrapUpDone(call.getId(), now), "tabulation");
                agents.save(agent);
            });
        }
    }
}
