package br.com.gestorcobranca.payment.domain.model;

/** Como o pagamento foi confirmado: pelo provedor, automaticamente (externo até o limite de risco), por segunda pessoa (acima do limite) ou pelo extrato/arquivo de retorno. */
public enum ReviewMode {
    PROVIDER, AUTO_LOW_RISK, DUAL_CONTROL, STATEMENT
}
