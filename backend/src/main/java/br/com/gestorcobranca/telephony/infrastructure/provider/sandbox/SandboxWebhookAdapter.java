package br.com.gestorcobranca.telephony.infrastructure.provider.sandbox;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook do simulador, no formato mais comum de CPaaS: cabeçalho
 * {@code X-Sandbox-Signature: t=<epoch s>,v1=<hex>} com {@code v1 = HMAC-SHA256(webhookSecret,
 * t + "." + corpo)}. A assinatura cobre o instante: entregas assinadas fora da tolerância
 * ({@code app.telephony.webhook.max-clock-skew}) são recusadas (replay). Comparação em tempo
 * constante. Corpo: {@code {"events":[{"id","type","callId","reference","timestamp","amd",
 * "hangupBy","cause","from","recordingRef","recordingDuration"}]}}.
 */
@Component
@ConditionalOnProperty(prefix = "app.telephony.sandbox", name = "enabled", havingValue = "true")
class SandboxWebhookAdapter implements TelephonyWebhookAdapter {

    static final String HEADER = "x-sandbox-signature";

    private final JsonCodec json;
    private final Duration tolerance;

    SandboxWebhookAdapter(JsonCodec json, TelephonyProperties properties) {
        this.json = json;
        this.tolerance = properties.webhook().maxClockSkew();
    }

    @Override
    public ProviderType provider() {
        return ProviderType.SANDBOX;
    }

    @Override
    public boolean validSignature(Map<String, String> credentials, Map<String, String> headers, byte[] body, Instant now) {
        String secret = credentials.get("webhookSecret");
        String header = headers == null ? null : headers.get(HEADER);
        if (secret == null || secret.isBlank() || header == null || body == null) {
            return false;
        }
        Long timestamp = null;
        byte[] received = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                if ("t".equals(kv[0])) {
                    timestamp = Long.parseLong(kv[1].trim());
                } else if ("v1".equals(kv[0])) {
                    received = HexFormat.of().parseHex(kv[1].trim().toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (timestamp == null || received == null) {
            return false;
        }
        Instant signedAt = Instant.ofEpochSecond(timestamp);
        if (signedAt.isBefore(now.minus(tolerance)) || signedAt.isAfter(now.plus(tolerance))) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, timestamp, body), received);
    }

    static byte[] sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<ProviderCallEvent> parse(byte[] body) {
        Payload payload;
        try {
            payload = json.fromJson(new String(body, StandardCharsets.UTF_8), Payload.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Corpo do webhook ilegível", e);
        }
        if (payload == null || payload.events() == null) {
            throw new IllegalArgumentException("Corpo do webhook sem eventos");
        }
        return payload.events().stream().map(SandboxWebhookAdapter::event).toList();
    }

    private static ProviderCallEvent event(Event e) {
        if (e == null || e.id() == null || e.type() == null) {
            throw new IllegalArgumentException("Evento sem id ou tipo");
        }
        ProviderCallEvent.Type type;
        Instant at;
        UUID reference;
        try {
            type = ProviderCallEvent.Type.valueOf(e.type().trim().toUpperCase(Locale.ROOT));
            at = e.timestamp() == null ? null : Instant.parse(e.timestamp());
            reference = e.reference() == null ? null : UUID.fromString(e.reference());
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Evento inválido", ex);
        }
        return new ProviderCallEvent(e.id(), type, e.callId(), reference, at, e.amd(), e.hangupBy(), e.cause(), e.from(),
                e.recordingRef(), e.recordingDuration());
    }

    record Payload(List<Event> events) {
    }

    record Event(String id, String type, String callId, String reference, String timestamp, String amd, String hangupBy,
                 String cause, String from, String recordingRef, Integer recordingDuration) {
    }
}
