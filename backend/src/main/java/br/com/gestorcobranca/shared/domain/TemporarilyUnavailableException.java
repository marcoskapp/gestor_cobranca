package br.com.gestorcobranca.shared.domain;

/**
 * Recurso ocupado ou indisponível por pouco tempo (ex.: limite de leituras de PDF simultâneas): o pedido pode ser
 * repetido em instantes (HTTP 503 com {@code Retry-After}). Não é erro de dados: um job que receba esta exceção deve
 * repetir, nunca tratá-la como falha permanente.
 */
public class TemporarilyUnavailableException extends DomainException {

    private final int retryAfterSeconds;

    public TemporarilyUnavailableException(String code, String message, int retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
