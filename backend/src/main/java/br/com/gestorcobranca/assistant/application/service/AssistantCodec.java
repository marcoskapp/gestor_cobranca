package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Conversões JSON do módulo (parâmetros da política, memória da sessão, listas) e SHA-256. */
@Component
class AssistantCodec {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Criterion>> CRITERIA = new TypeReference<>() {
    };

    /** Critério do Pix avaliado no servidor, como gravado em {@code pix_request.criteria}. */
    record Criterion(String criterion, boolean passed, String detail) {
    }

    private final JsonCodec json;

    AssistantCodec(JsonCodec json) {
        this.json = json;
    }

    String settings(PolicySettings settings) {
        return json.toJson(settings);
    }

    PolicySettings settings(String value) {
        return json.fromJson(value, PolicySettings.class);
    }

    String memory(SessionMemory memory) {
        return json.toJson(memory);
    }

    SessionMemory memory(String value) {
        return value == null || value.isBlank() ? SessionMemory.EMPTY : json.fromJson(value, SessionMemory.class);
    }

    String toJson(Object value) {
        return json.toJson(value);
    }

    Map<String, Object> toMap(String value) {
        return json.toMap(value);
    }

    List<String> strings(String value) {
        return value == null || value.isBlank() ? List.of() : json.fromJson(value, STRINGS);
    }

    List<Criterion> criteria(String value) {
        return value == null || value.isBlank() ? List.of() : json.fromJson(value, CRITERIA);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
