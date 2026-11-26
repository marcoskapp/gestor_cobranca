package br.com.gestorcobranca.identity.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Leitura da atividade das pessoas de um credor na auditoria e no log de acesso (sob o RLS da assessoria). As pessoas
 * vêm sempre de {@code identity.creditor_user} com {@code creditor_id = :creditorId} — nunca da coluna
 * {@code creditor_id} da auditoria.
 */
public interface CreditorActivityReader {

    /**
     * @param person        só esta pessoa (tem de ser do credor); {@code null} = todas as pessoas do credor
     * @param auditActions  ações de auditoria da lista branca
     * @param accessEvents  tipos do log de acesso da lista branca
     * @param since         início da janela (inclusivo)
     * @param beforeAt      posição do cursor: itens estritamente anteriores a ({@code beforeAt}, {@code beforeId})
     * @param limit         itens a ler (o serviço pede um a mais para saber se há página seguinte)
     */
    List<ActivityRow> read(UUID creditorId, UUID person, Collection<String> auditActions,
                           Collection<String> accessEvents, String downloadOrigin, Instant since, Instant beforeAt,
                           UUID beforeId, int limit);

    /**
     * @param source     AUDIT ou ACCESS
     * @param code       ação da auditoria ou tipo do log de acesso
     * @param entityType tipo do registro (auditoria) ou {@code resource} do download
     * @param entityId   id do registro (texto; pode não ser UUID no log de acesso)
     */
    record ActivityRow(Instant occurredAt, UUID id, String source, String code, String entityType, String entityId,
                       UUID personId, String personName) {
    }
}
