package br.com.gestorcobranca.payment.domain.model;

/** Formato do arquivo de extrato/retorno. CNAB 240/400 são portas futuras (sem leitor). */
public enum StatementFormat {
    CSV_GENERIC, CNAB_240, CNAB_400
}
