package br.com.gestorcobranca.payment.domain.model;

/** Estorno informado pelo provedor (devolução Pix) ou registrado manualmente. */
public enum ReversalSource {
    PROVIDER, MANUAL
}
