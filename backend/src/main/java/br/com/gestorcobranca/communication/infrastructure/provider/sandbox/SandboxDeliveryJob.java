package br.com.gestorcobranca.communication.infrastructure.provider.sandbox;

import br.com.gestorcobranca.communication.application.port.in.ProviderCallbackUseCases;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.StatusUpdate;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Entrega e leitura simuladas de uma mensagem aceita pelo sandbox (como os webhooks do provedor). */
@Component
@ConditionalOnProperty(prefix = "app.communication.sandbox", name = "enabled", havingValue = "true")
class SandboxDeliveryJob implements JobHandler<SandboxDeliveryJob.Payload> {

    static final String TYPE = "communication.sandbox_delivery";

    private final ProviderCallbackUseCases callbacks;
    private final Clock clock;

    SandboxDeliveryJob(ProviderCallbackUseCases callbacks, Clock clock) {
        this.callbacks = callbacks;
        this.clock = clock;
    }

    record Payload(UUID accountId, String providerMessageId) {
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
        callbacks.status(payload.accountId(), new StatusUpdate(payload.providerMessageId(), "DELIVERED", now, null, null));
        callbacks.status(payload.accountId(), new StatusUpdate(payload.providerMessageId(), "READ", now, null, null));
    }
}
