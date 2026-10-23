package br.com.gestorcobranca.payment.domain.model;

/** Origem do pagamento: integrado (provedor: webhook, consulta ou arquivo de retorno) ou externo (informado por operador com evidência). */
public enum PaymentSource {
    PROVIDER, EXTERNAL
}
