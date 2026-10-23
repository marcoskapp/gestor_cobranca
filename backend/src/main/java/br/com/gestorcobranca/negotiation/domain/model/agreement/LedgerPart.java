package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;

/**
 * Componente do razão do título no módulo debt ({@code DebtLedgerPort.ComponentAmounts}). A
 * correção monetária da negociação é reconhecida como encargo ({@link #FEES}) e os juros do
 * parcelamento do acordo como juros ({@link #INTEREST}).
 */
public enum LedgerPart {
    PRINCIPAL, INTEREST, FINE, FEES, HONORARIUM;

    public static LedgerPart of(DebtComponent component) {
        return switch (component) {
            case PRINCIPAL -> PRINCIPAL;
            case CORRECTION, FEES -> FEES;
            case INTEREST -> INTEREST;
            case FINE -> FINE;
            case HONORARIUM -> HONORARIUM;
        };
    }

    /** Componentes que precisam ser reconhecidos como encargo antes de receber pagamento. */
    public boolean isCharge() {
        return this != PRINCIPAL;
    }
}
