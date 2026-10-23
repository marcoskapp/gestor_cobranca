package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.util.Locale;

/** Conversão de textos da API em enums do domínio, com erro de negócio estável. */
final class ComplianceEnums {

    private ComplianceEnums() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String errorCode, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(errorCode, label + " obrigatório");
        }
        return parseOptional(type, value, errorCode, label);
    }

    /** Nulo ou em branco → nulo. */
    static <E extends Enum<E>> E parseOptional(Class<E> type, String value, String errorCode, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(errorCode, label + " inválido: " + value.trim());
        }
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
