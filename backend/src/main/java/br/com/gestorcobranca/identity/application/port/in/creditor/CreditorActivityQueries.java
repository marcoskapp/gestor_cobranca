package br.com.gestorcobranca.identity.application.port.in.creditor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Minha atividade" do portal do credor (fatia 7; RS-47 a RS-49). Só o ator {@code CREDITOR}; o credor e a pessoa vêm
 * do ator. Lê a auditoria (ator {@code CREDITOR}) e o log de acesso das pessoas do credor do ator — incluídas as
 * revogadas —, <b>nunca</b> pela coluna {@code creditor_id} da auditoria (que também marca ações da assessoria sobre o
 * credor). Só as ações da lista branca, como código curto e referência; janela dos últimos {@value #WINDOW_DAYS} dias.
 */
public interface CreditorActivityQueries {

    int WINDOW_DAYS = 90;
    int MAX_PAGE_SIZE = 50;

    /**
     * @param team   {@code false}: só a própria pessoa ({@code CREDITOR_PORTAL_OVERVIEW}); {@code true}: todas as pessoas
     *               do credor ({@code CREDITOR_ACTIVITY_VIEW}, senão 403 {@code ACCESS_DENIED}), com registro
     *               {@code USER_DIRECTORY_QUERIED}
     * @param before cursor opaco devolvido na página anterior ({@code null} = mais recentes)
     * @param size   de 1 a {@value #MAX_PAGE_SIZE}
     */
    CreditorActivityPage activity(boolean team, String before, int size);

    /** @param nextCursor cursor da página seguinte; nulo quando não há mais itens */
    record CreditorActivityPage(List<CreditorActivityItem> items, String nextCursor) {
    }

    /**
     * @param action        STATEMENT_ACKNOWLEDGED, STATEMENT_CONTESTED, DISPUTE_RESPONDED, PAYMENT_REPORTED, SIGNED_IN,
     *                      SIGNED_OUT, PASSWORD_CHANGED, MFA_ENROLLED ou FILE_DOWNLOADED
     * @param mine          feita pela própria pessoa da sessão
     * @param personName    nome da pessoa do credor (nunca e-mail)
     * @param referenceType STATEMENT, DISPUTE, PAYMENT, STATEMENT_FILE, DISPUTE_DOCUMENT ou nulo
     */
    record CreditorActivityItem(Instant occurredAt, String action, boolean mine, String personName,
                                String referenceType, UUID referenceId) {
    }
}
