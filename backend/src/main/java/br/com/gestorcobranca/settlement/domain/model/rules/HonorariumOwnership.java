package br.com.gestorcobranca.settlement.domain.model.rules;

/**
 * A quem pertencem os honorários cobrados do devedor (componente {@code HONORARIUM} do razão): à
 * assessoria (retidos, fora da base de comissão, na fração contratual) ou ao credor (repassados).
 */
public enum HonorariumOwnership {
    AGENCY,
    CREDITOR
}
