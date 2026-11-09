package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Conversão de textos da API em enums do domínio, com erro de negócio estável. */
final class ComplaintEnums {

    private ComplaintEnums() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String errorCode, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(errorCode, label + " obrigatório");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(errorCode, label + " inválido: " + value.trim());
        }
    }

    /** Nulo ou vazio → nulo (sem filtro). */
    static <E extends Enum<E>> Set<E> parseSet(Class<E> type, Collection<String> values, String errorCode, String label) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<E> out = EnumSet.noneOf(type);
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(parse(type, v, errorCode, label));
            }
        }
        return out.isEmpty() ? null : out;
    }
}
