package br.com.gestorcobranca.shared.domain;

import java.util.Map;

/** Violação de regra de negócio (HTTP 422). */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}
