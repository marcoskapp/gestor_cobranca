package br.com.gestorcobranca.compliance.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida de uma solicitação de titular:
 * <pre>
 * RECEIVED          → IDENTITY_VERIFIED, REJECTED
 * IDENTITY_VERIFIED → IN_PROGRESS, REJECTED
 * IN_PROGRESS       → COMPLETED, REJECTED
 * COMPLETED, REJECTED → finais
 * </pre>
 */
public enum RequestStatus {
    RECEIVED,
    IDENTITY_VERIFIED,
    IN_PROGRESS,
    COMPLETED,
    REJECTED;

    public Set<RequestStatus> allowedTransitions() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(IDENTITY_VERIFIED, REJECTED);
            case IDENTITY_VERIFIED -> EnumSet.of(IN_PROGRESS, REJECTED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED, REJECTED);
            case COMPLETED, REJECTED -> EnumSet.noneOf(RequestStatus.class);
        };
    }

    public boolean canTransitionTo(RequestStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED;
    }
}
