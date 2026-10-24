package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.telephony.domain.model.Agent.PresenceChange;
import br.com.gestorcobranca.telephony.domain.model.PresenceInterval;
import br.com.gestorcobranca.telephony.domain.model.PresenceStatus;
import br.com.gestorcobranca.telephony.domain.repository.PresenceIntervalRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Efeitos de uma mudança de presença, na transação da mudança: intervalo encerrado (append-only),
 * evento de negócio {@code agent.presence_changed}, métrica e — quando o operador fica disponível
 * numa campanha — um ciclo imediato do discador.
 */
@Component
class PresenceRecorder {

    private final PresenceIntervalRepository intervals;
    private final BusinessEventLogger businessEvents;
    private final TelephonyMetrics metrics;
    private final DialerTicks ticks;

    PresenceRecorder(PresenceIntervalRepository intervals, BusinessEventLogger businessEvents, TelephonyMetrics metrics,
                     DialerTicks ticks) {
        this.intervals = intervals;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.ticks = ticks;
    }

    void record(Optional<PresenceChange> change, String trigger) {
        change.ifPresent(c -> record(c, trigger));
    }

    /** @param trigger operator, call, tabulation, supervisor, timeout, configuration */
    void record(PresenceChange change, String trigger) {
        if (change.from() != PresenceStatus.OFFLINE) {
            intervals.save(new PresenceInterval(change));
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.AGENT_PRESENCE_CHANGED)
                .reference("telephony_agent", change.agentId())
                .durationMs(change.durationSeconds() * 1000)
                .attr("userId", change.userId())
                .attr("from", change.from())
                .attr("to", change.to())
                .attr("fromReason", change.fromReason())
                .attr("reason", change.toReason())
                .attr("productivePause", change.toReason() == null ? null : change.toReason().isProductive())
                .attr("campaignId", change.campaignId())
                .attr("trigger", trigger));
        metrics.presence(change.to().name(), change.toReason() == null ? null : change.toReason().name());
        if (change.to() == PresenceStatus.AVAILABLE && change.campaignId() != null) {
            ticks.requestNow(change.campaignId());
        }
    }
}
