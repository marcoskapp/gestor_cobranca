package br.com.gestorcobranca.telephony.infrastructure.provider.sandbox;

import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Provedor simulado para ambiente local e testes ({@code app.telephony.sandbox.enabled=true};
 * recusado no perfil {@code prod} por {@code TelephonySandboxGuard}). Nenhuma rede: aceita as
 * discagens com ids {@code sbx-call-*} e, com {@code auto-progress}, agenda o toque e o atendimento
 * por pessoa (job {@code telephony.sandbox_progress}); nos testes os eventos são conduzidos por
 * {@code POST /api/v1/telephony/sandbox/calls/{id}/events} ou pelo webhook assinado.
 * Destinos terminados em {@code 0000} simulam número inexistente (recusa) e em {@code 9999}, falha
 * transitória. A gravação devolvida é um WAV de 1 s de silêncio.
 */
@Component
@ConditionalOnProperty(prefix = "app.telephony.sandbox", name = "enabled", havingValue = "true")
class SandboxTelephonyProvider implements TelephonyProvider {

    private final JobQueue jobQueue;
    private final TelephonyProperties properties;
    private final MeterRegistry meterRegistry;

    SandboxTelephonyProvider(JobQueue jobQueue, TelephonyProperties properties, MeterRegistry meterRegistry) {
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.SANDBOX;
    }

    @Override
    public DialResult originate(OriginateRequest request) {
        return dial(request.context().accountId(), request.callId(), request.destination(), "originate");
    }

    @Override
    public List<DialResult> dialBatch(ProviderContext context, List<DialRequest> requests) {
        if (requests.stream().anyMatch(r -> r.destination() != null && r.destination().endsWith("9999"))) {
            count("dial_batch", "transient");
            throw new TransientProviderException("Falha transitória simulada");
        }
        return requests.stream().map(r -> dial(context.accountId(), r.callId(), r.destination(), "dial_batch")).toList();
    }

    private DialResult dial(UUID accountId, UUID callId, String destination, String operation) {
        if (destination != null && destination.endsWith("9999")) {
            count(operation, "transient");
            throw new TransientProviderException("Falha transitória simulada");
        }
        if (destination == null || destination.endsWith("0000")) {
            count(operation, "rejected");
            return DialResult.rejected(callId, "SANDBOX_INVALID_NUMBER", "Número inexistente (simulado)");
        }
        String providerCallId = "sbx-call-" + UUID.randomUUID();
        if (properties.sandbox().autoProgress()) {
            jobQueue.enqueue(SandboxProgressJob.TYPE, new SandboxProgressJob.Payload(accountId, providerCallId, callId),
                    null, null);
        }
        count(operation, "success");
        return DialResult.accepted(callId, providerCallId);
    }

    @Override
    public void bridge(BridgeRequest request) {
        count("bridge", "success");
    }

    @Override
    public void hangup(HangupRequest request) {
        count("hangup", "success");
    }

    @Override
    public RecordingMedia fetchRecording(ProviderContext context, String recordingRef) {
        byte[] wav = silentWav();
        count("fetch_recording", "success");
        return new RecordingMedia("audio/wav", wav.length, new ByteArrayInputStream(wav));
    }

    @Override
    public void deleteRecording(ProviderContext context, String recordingRef) {
        count("delete_recording", "success");
    }

    /** WAV PCM 8 kHz, 16 bits, mono, 1 s de silêncio. */
    static byte[] silentWav() {
        int samples = 8000;
        int dataSize = samples * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2)
                .putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        return buffer.array();
    }

    private void count(String operation, String outcome) {
        meterRegistry.counter("integration.calls", "provider", "sandbox", "operation", operation, "outcome", outcome)
                .increment();
    }
}
