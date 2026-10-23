package br.com.gestorcobranca.payment.domain.model;

/** Distribuição entre títulos avulsos sem valor indicado: vencidos primeiro (CC art. 355) ou proporcional ao saldo. */
public enum TitleStrategy {
    OLDEST_FIRST, PROPORTIONAL
}
