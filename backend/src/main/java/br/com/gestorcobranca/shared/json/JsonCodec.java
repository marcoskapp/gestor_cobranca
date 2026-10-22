package br.com.gestorcobranca.shared.json;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Serialização JSON centralizada (Jackson 3). Em entidades JPA, atributos JSON são declarados
 * como {@code String} com {@code @JdbcTypeCode(SqlTypes.JSON)} e convertidos por este componente
 * na camada de aplicação/infraestrutura — assim o mapeamento não depende do suporte de JSON do
 * Hibernate a uma versão específica do Jackson.
 */
@Component
public class JsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public <T> T fromJson(String json, TypeReference<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public Map<String, Object> toMap(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, MAP_TYPE);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
