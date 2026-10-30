package br.com.gestorcobranca.shared.domain;

/**
 * Pré-condição de concorrência otimista não atendida (HTTP 412): o cliente enviou {@code If-Match} com uma versão
 * (ETag) que já não é a atual do recurso. Usada pela API pública; o cliente deve reler o recurso e reaplicar a
 * alteração.
 */
public class PreconditionFailedException extends DomainException {

    public PreconditionFailedException(String code, String message) {
        super(code, message);
    }
}
