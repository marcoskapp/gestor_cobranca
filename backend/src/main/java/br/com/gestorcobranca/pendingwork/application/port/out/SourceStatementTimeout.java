package br.com.gestorcobranca.pendingwork.application.port.out;

import java.time.Duration;

/**
 * Limite de tempo das instruções SQL na transação corrente: uma fonte lenta é interrompida no
 * banco, e não só abandonada pela requisição.
 */
public interface SourceStatementTimeout {

    void apply(Duration timeout);
}
