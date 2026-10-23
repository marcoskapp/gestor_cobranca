package br.com.gestorcobranca.negotiation.domain.model.proposal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida da proposta (§5):
 * <pre>
 * dentro da alçada ──────────────► DRAFT ──────────┐
 * acima da alçada (humano) ──► PENDING_APPROVAL ─► APPROVED ─┴─► ACCEPTED (devedor) ─► FORMALIZED
 *                                    └─► REJECTED
 * acima da alçada (assistente) ──► HANDED_OFF (transferência para humano, sugestão)
 * DRAFT/PENDING_APPROVAL/APPROVED/ACCEPTED ── validade vencida ──► EXPIRED
 * </pre>
 */
public enum ProposalStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, ACCEPTED, FORMALIZED, EXPIRED, HANDED_OFF;

    private static final Set<ProposalStatus> OPEN = EnumSet.of(DRAFT, PENDING_APPROVAL, APPROVED, ACCEPTED);

    /** Ainda pode avançar (sujeita a expiração). */
    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
