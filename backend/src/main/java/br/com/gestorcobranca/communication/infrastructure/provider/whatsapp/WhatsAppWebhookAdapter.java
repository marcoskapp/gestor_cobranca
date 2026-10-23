package br.com.gestorcobranca.communication.infrastructure.provider.whatsapp;

import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notificações da WhatsApp Cloud API. A assinatura {@code X-Hub-Signature-256: sha256=<hex>} é o
 * HMAC-SHA256 do corpo bruto com o App Secret da conta, comparado em tempo constante. O handshake
 * compara o {@code hub.verify_token} com o configurado na conta.
 */
@Component
class WhatsAppWebhookAdapter implements ProviderWebhookAdapter {

    private static final String PREFIX = "sha256=";

    private final JsonCodec json;

    WhatsAppWebhookAdapter(JsonCodec json) {
        this.json = json;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.WHATSAPP_CLOUD;
    }

    @Override
    public boolean validSignature(Map<String, String> credentials, String signatureHeader, byte[] body) {
        String secret = credentials.get("appSecret");
        if (secret == null || secret.isBlank() || signatureHeader == null || body == null
                || !signatureHeader.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), received);
    }

    @Override
    public boolean validVerifyToken(Map<String, String> credentials, String token) {
        String expected = credentials.get("verifyToken");
        if (expected == null || expected.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InboundBatch parse(byte[] body) {
        JsonNode root;
        try {
            root = json.mapper().readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Corpo de webhook inválido", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Corpo de webhook inválido");
        }
        List<InboundMessage> messages = new ArrayList<>();
        List<StatusUpdate> statuses = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if (!"messages".equals(change.path("field").asString(""))) {
                    continue;
                }
                JsonNode value = change.path("value");
                for (JsonNode m : value.path("messages")) {
                    String id = m.path("id").asString("");
                    if (id.isBlank()) {
                        continue;
                    }
                    messages.add(new InboundMessage(id, m.path("from").asString(null), m.path("type").asString("unknown"),
                            text(m), instant(m.path("timestamp"))));
                }
                for (JsonNode s : value.path("statuses")) {
                    String id = s.path("id").asString("");
                    if (id.isBlank()) {
                        continue;
                    }
                    JsonNode error = s.path("errors").path(0);
                    String errorCode = error.isMissingNode() ? null : error.path("code").asString(null);
                    String errorTitle = error.isMissingNode() ? null : error.path("title").asString(null);
                    statuses.add(new StatusUpdate(id, s.path("status").asString("").toUpperCase(Locale.ROOT),
                            instant(s.path("timestamp")), errorCode, errorTitle));
                }
            }
        }
        return new InboundBatch(messages, statuses);
    }

    /** Texto conforme o tipo: texto, botão, resposta interativa ou legenda de mídia. */
    private static String text(JsonNode m) {
        String type = m.path("type").asString("");
        return switch (type) {
            case "text" -> m.path("text").path("body").asString(null);
            case "button" -> m.path("button").path("text").asString(null);
            case "interactive" -> {
                JsonNode interactive = m.path("interactive");
                String button = interactive.path("button_reply").path("title").asString(null);
                yield button != null ? button : interactive.path("list_reply").path("title").asString(null);
            }
            case "image", "video", "document" -> m.path(type).path("caption").asString(null);
            default -> null;
        };
    }

    private static Instant instant(JsonNode timestamp) {
        String value = timestamp.asString("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular HMAC", e);
        }
    }
}
