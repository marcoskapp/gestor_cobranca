package br.com.gestorcobranca.telephony.domain.model;

/** Como a chamada nasceu. */
public enum CallOrigin {
    /** Clique para ligar da estação de cobrança (operador primeiro, depois o devedor). */
    CLICK_TO_CALL,
    /** Campanha de discagem (progressiva ou preditiva): devedor primeiro, operador livre ao atender. */
    DIALER,
    /** Chamada recebida (retorno do devedor): não passa pelo porteiro de contato. */
    INBOUND;

    /** Origem informada ao porteiro de contato do compliance ({@code OPERATOR} ou {@code CAMPAIGN}). */
    public String gateOrigin() {
        return this == DIALER ? "CAMPAIGN" : "OPERATOR";
    }
}
