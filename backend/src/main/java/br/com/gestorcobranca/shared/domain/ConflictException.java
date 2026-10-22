package br.com.gestorcobranca.shared.domain;

import java.util.Map;

/** Conflito de estado: transição inválida, duplicidade, versão desatualizada (HTTP 409). */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }

    public ConflictException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}
