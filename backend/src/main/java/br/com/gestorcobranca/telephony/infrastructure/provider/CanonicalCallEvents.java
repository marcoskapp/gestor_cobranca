package br.com.gestorcobranca.telephony.infrastructure.provider;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Formato canônico de eventos de chamada do Gestor (v1), publicado por conectores de provedores
 * cuja plataforma não entrega webhooks no formato do adaptador (ex.: eventos de CTI convertidos):
 * {@code {"events":[{"id","type","callId","reference","timestamp","amd","hangupBy","cause","from",
 * "recordingRef","recordingDuration"}]}}, com {@code type} ∈ {@code RINGING, ANSWERED, COMPLETED,
 * NO_ANSWER, BUSY, FAILED, CANCELLED, INBOUND, RECORDING_AVAILABLE}, {@code timestamp} ISO-8601,
 * {@code reference} = id interno da chamada (referência do cliente), {@code amd} ∈ {@code HUMAN,
 * MACHINE, UNKNOWN} e {@code hangupBy} ∈ {@code DEBTOR, OPERATOR, SYSTEM, PROVIDER}. Assinado com
 * {@link TimestampedHmac}.
 */
public final class CanonicalCallEvents {

    private CanonicalCallEvents() {
    }

    /** @throws IllegalArgumentException corpo ou evento inválido */
    public static List<ProviderCallEvent> parse(JsonCodec json, byte[] body) {
        Payload payload;
        try {
            payload = json.fromJson(new String(body, StandardCharsets.UTF_8), Payload.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Corpo do webhook ilegível", e);
        }
        if (payload == null || payload.events() == null) {
            throw new IllegalArgumentException("Corpo do webhook sem eventos");
        }
        return payload.events().stream().map(CanonicalCallEvents::event).toList();
    }

    private static ProviderCallEvent event(Event e) {
        if (e == null || e.id() == null || e.type() == null) {
            throw new IllegalArgumentException("Evento sem id ou tipo");
        }
        try {
            ProviderCallEvent.Type type = ProviderCallEvent.Type.valueOf(e.type().trim().toUpperCase(Locale.ROOT));
            Instant at = e.timestamp() == null ? null : Instant.parse(e.timestamp());
            UUID reference = e.reference() == null || e.reference().isBlank() ? null : UUID.fromString(e.reference());
            return new ProviderCallEvent(e.id(), type, e.callId(), reference, at, e.amd(), e.hangupBy(), e.cause(),
                    e.from(), e.recordingRef(), e.recordingDuration());
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Evento inválido", ex);
        }
    }

    record Payload(List<Event> events) {
    }

    record Event(String id, String type, String callId, String reference, String timestamp, String amd,
                 String hangupBy, String cause, String from, String recordingRef, Integer recordingDuration) {
    }
}
