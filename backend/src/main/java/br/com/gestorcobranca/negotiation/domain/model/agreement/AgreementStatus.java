package br.com.gestorcobranca.negotiation.domain.model.agreement;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida do acordo (RB05):
 * <pre>
 * FORMALIZED ─1º pagamento─► IN_PAYMENT ─todas as parcelas pagas─► SETTLED
 *     │                         │  ▲                                  │
 *     │                         │  └──────── estorno ─────────────────┘
 *     ├──── quebra (regra da política) ───► BROKEN ──┐
 *     ├──── cancelamento (AGREEMENT_CANCEL) ─► CANCELLED
 *     └──── substituição por renegociação ───────────┴─► RENEGOTIATED
 * </pre>
 */
public enum AgreementStatus {
    FORMALIZED, IN_PAYMENT, SETTLED, BROKEN, CANCELLED, RENEGOTIATED;

    private static final Set<AgreementStatus> ACTIVE = EnumSet.of(FORMALIZED, IN_PAYMENT);

    /** Em vigor: parcelas em aberto, títulos vinculados, sujeito à regra de quebra. */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** Pode ser substituído por uma renegociação (em vigor ou quebrado). */
    public boolean isRenegotiable() {
        return isActive() || this == BROKEN;
    }
}
