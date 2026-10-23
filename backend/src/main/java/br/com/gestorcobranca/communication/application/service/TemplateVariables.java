package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON das variáveis de template e dos valores informados num envio. */
@Component
class TemplateVariables {

    private static final TypeReference<List<TemplateVariable>> LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> VALUES = new TypeReference<>() {
    };

    private final JsonCodec json;

    TemplateVariables(JsonCodec json) {
        this.json = json;
    }

    List<TemplateVariable> parse(String stored) {
        return stored == null || stored.isBlank() ? List.of() : json.fromJson(stored, LIST);
    }

    String toJson(List<TemplateVariable> variables) {
        return json.toJson(variables);
    }

    String valuesToJson(Map<String, String> values) {
        return values == null || values.isEmpty() ? null : json.toJson(new TreeMap<>(values));
    }

    Map<String, String> parseValues(String stored) {
        return stored == null || stored.isBlank() ? Map.of() : json.fromJson(stored, VALUES);
    }
}
