package br.com.gestorcobranca.communication.infrastructure.provider.sandbox;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Provedor simulado para ambiente local e testes ({@code app.communication.sandbox.enabled=true};
 * recusado no perfil {@code prod} por {@code SandboxGuard}). Aceita o envio e agenda a entrega e a
 * leitura simuladas (job {@code communication.sandbox_delivery}). Marcadores no texto permitem
 * exercitar falhas: {@code [sandbox:reject]} (recusa definitiva) e {@code [sandbox:transient]}
 * (falha transitória).
 */
@Component
@ConditionalOnProperty(prefix = "app.communication.sandbox", name = "enabled", havingValue = "true")
class SandboxGateway implements ChannelGateway {

    static final String REJECT = "[sandbox:reject]";
    static final String TRANSIENT = "[sandbox:transient]";

    private final JobQueue jobQueue;
    private final CommunicationProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    SandboxGateway(JobQueue jobQueue, CommunicationProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.SANDBOX;
    }

    @Override
    public SendResult send(OutboundRequest request) {
        String text = request.text() == null ? "" : request.text();
        if (text.contains(TRANSIENT)) {
            count("transient");
            throw new TransientSendException("Falha transitória simulada");
        }
        if (text.contains(REJECT)) {
            count("rejected");
            return SendResult.rejected("SANDBOX_REJECTED", "Recusa simulada");
        }
        String providerId = "sbx-" + UUID.randomUUID();
        jobQueue.enqueue(SandboxDeliveryJob.TYPE, new SandboxDeliveryJob.Payload(request.accountId(), providerId),
                Instant.now(clock).plus(properties.sandbox().deliveryDelay()), null);
        count("success");
        return SendResult.accepted(providerId);
    }

    private void count(String outcome) {
        meterRegistry.counter("integration.calls", "provider", "sandbox", "operation", "send_message", "outcome", outcome)
                .increment();
    }
}
