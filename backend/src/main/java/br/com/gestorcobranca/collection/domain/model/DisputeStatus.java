package br.com.gestorcobranca.collection.domain.model;

/** {@code OPEN → UNDER_REVIEW → RESOLVED}, ou {@code OPEN → RESOLVED}; {@code WITHDRAWN} quando o devedor desiste. */
public enum DisputeStatus {
    OPEN,
    /** Encaminhada ao credor / em análise. */
    UNDER_REVIEW,
    RESOLVED,
    WITHDRAWN;

    public boolean isOpen() {
        return this == OPEN || this == UNDER_REVIEW;
    }
}
