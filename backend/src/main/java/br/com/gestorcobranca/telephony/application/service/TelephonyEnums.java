package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Conversão de textos da API em enums do domínio, com erro de negócio estável. */
final class TelephonyEnums {

    private TelephonyEnums() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(code, label + " obrigatório");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "%s inválido: use %s".formatted(label,
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))));
        }
    }

    static <E extends Enum<E>> E parseOptional(Class<E> type, String value, String code, String label) {
        return value == null || value.isBlank() ? null : parse(type, value, code, label);
    }
}
