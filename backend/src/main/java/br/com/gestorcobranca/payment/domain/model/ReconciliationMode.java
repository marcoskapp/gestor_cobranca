package br.com.gestorcobranca.payment.domain.model;

/** Como o pagamento foi conciliado: pela consulta à API do PSP, por linha de extrato/arquivo de retorno ou manualmente (resolução de divergência). */
public enum ReconciliationMode {
    PROVIDER_API, STATEMENT, MANUAL
}
