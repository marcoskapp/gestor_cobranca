package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Serialização dos segredos da conta Pix (objeto JSON de textos). O texto resultante é gravado
 * criptografado pela entidade; aqui só circula em memória e nunca vai para log ou auditoria.
 */
@Component
class PixCredentialCodec {

    static final String CLIENT_ID = "clientId";
    static final String CLIENT_SECRET = "clientSecret";
    static final String WEBHOOK_SECRET = "webhookSecret";
    static final String CERTIFICATE_PASSWORD = "certificatePassword";

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    private final JsonCodec json;

    PixCredentialCodec(JsonCodec json) {
        this.json = json;
    }

    String encode(Map<String, String> credentials) {
        return json.toJson(new TreeMap<>(credentials));
    }

    Map<String, String> decode(String stored) {
        return stored == null || stored.isBlank() ? Map.of() : json.fromJson(stored, TYPE);
    }

    Set<String> keys(String stored) {
        return decode(stored).keySet();
    }
}
