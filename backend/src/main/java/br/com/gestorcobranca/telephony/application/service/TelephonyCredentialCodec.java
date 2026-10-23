package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Serialização das credenciais do provedor (objeto JSON de textos). O texto resultante é gravado
 * criptografado pela entidade; aqui só circula em memória e nunca vai para log.
 */
@Component
class TelephonyCredentialCodec {

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    private final JsonCodec json;

    TelephonyCredentialCodec(JsonCodec json) {
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
