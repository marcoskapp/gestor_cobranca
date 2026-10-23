package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import java.io.Serial;

/**
 * Transporta, através do parser SAX ou do iterador de registros, uma exceção lançada pelo
 * consumidor das linhas, para que ela seja propagada como foi lançada e não confundida com um
 * problema do arquivo.
 */
final class ConsumerFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ConsumerFailure(RuntimeException cause) {
        super(cause);
    }

    RuntimeException original() {
        return (RuntimeException) getCause();
    }

    /** Procura a falha do consumidor na cadeia de causas (parsers podem embrulhá-la). */
    static RuntimeException find(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConsumerFailure failure) {
                return failure.original();
            }
        }
        return null;
    }
}
