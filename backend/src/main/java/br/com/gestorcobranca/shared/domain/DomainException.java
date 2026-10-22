package br.com.gestorcobranca.shared.domain;

import java.util.Map;

/**
 * Base das exceções de negócio. Toda exceção de domínio carrega um {@code code} estável
 * (ex.: {@code agreement.discount_above_limit}) usado pelo frontend para tradução e pelos
 * logs/métricas para agregação. A mensagem é legível, em português, e nunca contém dados
 * pessoais.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> details;

    protected DomainException(String code, String message) {
        this(code, message, Map.of());
    }

    protected DomainException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
