package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;

import java.util.UUID;

/**
 * Motivo de recusa de uma condição, com código estável (contrato com telas, assistente e métricas).
 *
 * @param component componente afetado (descontos), se houver
 * @param titleId   título afetado (elegibilidade), se houver
 */
public record Violation(ViolationKind kind, String code, String message, DebtComponent component, UUID titleId) {

    public static Violation of(ViolationKind kind, String code, String message) {
        return new Violation(kind, code, message, null, null);
    }
}
