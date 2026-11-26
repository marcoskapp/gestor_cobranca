package br.com.gestorcobranca.ingestion.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lote bloqueado por titular anonimizado (SEG-20261124-D01h, fatia 5; decisões 1469 e 1497): as linhas do titular no
 * arquivo (referência mascarada, sem nome nem documento), o texto pronto do pedido de novo arquivo ao credor, a
 * situação do aviso ao credor (compliance) e o registro de que o pedido foi enviado.
 */
public interface ImportSubjectBlockUseCases {

    /** Exige {@code IMPORT_VIEW} e o lote visível ao ator (404 fora do escopo). Lote não bloqueado → 404. */
    SubjectBlockView forBatch(UUID batchId);

    /**
     * Registra que o credor foi avisado do pedido de novo arquivo: uma vez só (409
     * {@code import_file_request.already_registered}), sem texto livre, data nem futura nem anterior ao bloqueio.
     * Exige {@code IMPORT_EXECUTE} e o credor do lote. Grava auditoria.
     */
    SubjectBlockView registerRequestSent(UUID batchId, RegisterRequestCommand command);

    /** @param channel EMAIL, PHONE, LETTER, MEETING ou OTHER */
    record RegisterRequestCommand(Instant sentAt, String channel) {
    }

    /**
     * @param linesTotal linhas do titular encontradas (pode passar das listadas)
     * @param protocol   início do SHA-256 do arquivo, para o credor achar o arquivo
     * @param message    texto pronto do pedido (sem dado pessoal)
     * @param notice     situação do aviso ao credor da eliminação (nula se não houver aviso ligado ao bloqueio)
     * @param request    pedido de novo arquivo registrado (nulo enquanto não enviado)
     * @param inProgress o lote ainda está em processamento (as linhas podem crescer; o pedido espera o fim)
     */
    record SubjectBlockView(UUID batchId, Instant blockedAt, String fileName, Instant fileSentAt, String protocol,
                            List<BlockLineView> lines, int linesTotal, String message, NoticeSituation notice,
                            RequestView request, boolean inProgress) {
    }

    /** @param matchedBy DEBTOR (devedor gravado) ou TITLE_REFERENCE (título do credor) */
    record BlockLineView(long rowNumber, String reference, String matchedBy) {
    }

    /** @param status PENDING ou NOTIFIED; {@code id} para o atalho ao aviso no compliance (com a permissão dele) */
    record NoticeSituation(UUID id, String status, Instant notifiedAt) {
    }

    /** @param sentBy quem registrou (a tela resolve o nome) */
    record RequestView(Instant sentAt, String channel, UUID sentBy, Instant registeredAt) {
    }
}
