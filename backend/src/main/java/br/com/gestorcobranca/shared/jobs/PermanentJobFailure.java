package br.com.gestorcobranca.shared.jobs;

/** Falha definitiva: o job vai direto para DEAD sem novas tentativas. */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
