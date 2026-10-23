package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Conversão de textos da API em enums do domínio, com erro 422 e código estável. */
final class PaymentEnums {

    private PaymentEnums() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String label) {
        E parsed = parseOptional(type, value, code, label);
        if (parsed == null) {
            throw new BusinessRuleException(code, label + " obrigatório");
        }
        return parsed;
    }

    static <E extends Enum<E>> E parseOptional(Class<E> type, String value, String code, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "%s inválido: %s (valores: %s)".formatted(label, value,
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))));
        }
    }

    static <E extends Enum<E>> Set<E> parseSet(Class<E> type, Collection<String> values, String code, String label) {
        EnumSet<E> result = EnumSet.noneOf(type);
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).forEach(v -> result.add(parse(type, v, code, label)));
        }
        return result;
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
