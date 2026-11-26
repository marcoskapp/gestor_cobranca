package br.com.gestorcobranca.creditorportal.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Minha atividade" no portal do credor (fatia 7): o que a pessoa (ou o time do credor, para quem tem
 * {@code CREDITOR_ACTIVITY_VIEW}) fez no portal, nos últimos 90 dias. Records próprios (lista de campos permitidos).
 */
public interface CreditorActivityPortalUseCases {

    /**
     * @param team   {@code true}: todas as pessoas do credor (403 sem {@code CREDITOR_ACTIVITY_VIEW})
     * @param before cursor opaco da página anterior ({@code null} = mais recentes)
     * @param size   de 1 a 50
     */
    ActivityPage activity(boolean team, String before, int size);

    /** @param scope OWN ou TEAM; @param nextCursor nulo quando não há mais itens */
    record ActivityPage(String scope, List<ActivityItem> items, String nextCursor, int windowDays) {
    }

    /**
     * @param action        STATEMENT_ACKNOWLEDGED, STATEMENT_CONTESTED, DISPUTE_RESPONDED, PAYMENT_REPORTED, SIGNED_IN,
     *                      SIGNED_OUT, PASSWORD_CHANGED, MFA_ENROLLED ou FILE_DOWNLOADED
     * @param mine          feita pela própria pessoa da sessão
     * @param referenceType STATEMENT, DISPUTE, PAYMENT, STATEMENT_FILE, DISPUTE_DOCUMENT ou nulo
     */
    record ActivityItem(Instant occurredAt, String action, boolean mine, String personName, String referenceType,
                        UUID referenceId) {
    }
}
