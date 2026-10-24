package br.com.gestorcobranca.settlement.domain.model.rules;

/** Tipo de recebimento para a regra de comissão: à vista (acordo de 1 parcela ou título avulso) ou parcelado. */
public enum PaymentType {
    LUMP_SUM,
    INSTALLMENTS
}
