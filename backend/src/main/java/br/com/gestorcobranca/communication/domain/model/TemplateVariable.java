package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.regex.Pattern;

/**
 * Variável declarada num template ({@code {{nome}}} no texto).
 *
 * @param name      identificador ({@code [a-zA-Z][a-zA-Z0-9_]*}, até 40)
 * @param type      tipo validado na renderização
 * @param required  obrigatória no envio
 * @param maxLength tamanho máximo do valor (TEXT e CODE; nulo = padrão do tipo)
 */
public record TemplateVariable(String name, VariableType type, boolean required, Integer maxLength) {

    public static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,39}");
    public static final int TEXT_MAX = 120;

    public TemplateVariable {
        Guard.require(name != null && NAME.matcher(name).matches(), "template.variable_name_invalid",
                "Nome de variável inválido: use letras, números e _ (até 40)");
        Guard.require(type != null, "template.variable_type_required", "Tipo da variável %s obrigatório".formatted(name));
        Guard.require(maxLength == null || (maxLength >= 1 && maxLength <= TEXT_MAX), "template.variable_length_invalid",
                "Tamanho máximo da variável %s entre 1 e %d".formatted(name, TEXT_MAX));
    }

    public int effectiveMaxLength() {
        if (maxLength != null) {
            return maxLength;
        }
        return switch (type) {
            case FIRST_NAME -> 30;
            case URL -> 300;
            case CODE -> 60;
            case TEXT -> 60;
            default -> 40;
        };
    }
}
