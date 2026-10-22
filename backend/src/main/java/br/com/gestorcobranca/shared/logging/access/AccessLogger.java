package br.com.gestorcobranca.shared.logging.access;

/**
 * Registra eventos de acesso e segurança em {@code shared.access_log} e no logger
 * {@code ACCESS}. A gravação ocorre em transação própria: o registro persiste mesmo que a
 * operação que o originou falhe (ex.: login recusado, acesso negado).
 */
public interface AccessLogger {

    void log(AccessEvent event);
}
