package br.com.gestorcobranca.telephony.infrastructure.provider.sandbox;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.telephony.application.port.in.ProviderEventUseCases;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Andamento simulado (ambiente local): toque e atendimento por pessoa, como os webhooks do provedor. */
@Component
@ConditionalOnProperty(prefix = "app.telephony.sandbox", name = "enabled", havingValue = "true")
class SandboxProgressJob implements JobHandler<SandboxProgressJob.Payload> {

    static final String TYPE = "telephony.sandbox_progress";

    private final ProviderEventUseCases events;
    private final Clock clock;

    SandboxProgressJob(ProviderEventUseCases events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    record Payload(UUID accountId, String providerCallId, UUID callId) {
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
        Instant now = Instant.now(clock);
        events.apply(payload.accountId(), new ProviderCallEvent("sbx-auto-ring-" + payload.providerCallId(), Type.RINGING,
                payload.providerCallId(), payload.callId(), now, null, null, null, null, null, null));
        events.apply(payload.accountId(), new ProviderCallEvent("sbx-auto-answer-" + payload.providerCallId(),
                Type.ANSWERED, payload.providerCallId(), payload.callId(), now, "HUMAN", null, null, null, null, null));
    }
}
