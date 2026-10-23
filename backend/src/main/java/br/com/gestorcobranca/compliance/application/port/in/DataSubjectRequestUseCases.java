package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.compliance.application.port.in.FileContent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Solicitações de titulares (LGPD art. 18) com fluxo validado e auditado:
 * {@code RECEIVED → IDENTITY_VERIFIED → IN_PROGRESS → COMPLETED}, com recusa fundamentada
 * ({@code REJECTED}) possível até a conclusão. Prazo calculado no recebimento (padrão 15 dias
 * corridos, configurável por tipo); solicitações vencidas são sinalizadas por job.
 * <p>
 * Ações automáticas ({@link #execute}): ACCESS/PORTABILITY geram o arquivo JSON com os dados do
 * titular; ANONYMIZATION elimina os dados pessoais no módulo debt (títulos em cobrança impedem —
 * a solicitação é recusada com fundamentação); OPPOSITION cria restrição de contato do tipo
 * DEBTOR_REQUEST para todos os credores e canais. A execução exige acesso a todos os credores:
 * o direito do titular alcança todos os seus dados na assessoria.
 */
public interface DataSubjectRequestUseCases {

    RequestView open(OpenRequestCommand command);

    /**
     * Confirma a identidade e vincula o titular. Com {@code debtorId} nulo, o titular é
     * localizado pelo documento informado na abertura; com ambos, o documento precisa ser o do
     * devedor ({@code dsr.document_mismatch}).
     */
    RequestView verifyIdentity(UUID requestId, UUID debtorId);

    /** Inicia o atendimento; {@code assignee} nulo mantém o responsável atual. */
    RequestView start(UUID requestId, UUID assignee);

    RequestView assign(UUID requestId, UUID assignee);

    /** Executa a ação automática do tipo (somente ACCESS, PORTABILITY, ANONYMIZATION, OPPOSITION). */
    RequestView execute(UUID requestId);

    /** Conclui com a resposta dada ao titular. Tipos executáveis exigem a execução antes. */
    RequestView complete(UUID requestId, String responseSummary);

    /** Recusa fundamentada (a justificativa é a resposta ao titular). */
    RequestView reject(UUID requestId, String justification);

    RequestView get(UUID requestId);

    /** Arquivo de dados do titular; registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    FileContent openResult(UUID requestId);

    /**
     * Filtros opcionais. Atores sem acesso a todos os credores precisam filtrar por um devedor
     * visível.
     *
     * @param document CPF/CNPJ do requerente (busca pelo blind index)
     */
    PageResponse<RequestView> search(RequestFilter filter, PageQuery page);

    /**
     * @param type     CONFIRMATION, ACCESS, CORRECTION, ANONYMIZATION, PORTABILITY,
     *                 INFORMATION_SHARING, OPPOSITION ou REVIEW_AUTOMATED_DECISION
     * @param channel  EMAIL, PHONE, WHATSAPP, PORTAL, LETTER, IN_PERSON ou CREDITOR_FORWARD
     * @param document CPF/CNPJ do requerente (opcional, recomendado)
     * @param receivedAt recebimento (nulo = agora; não pode estar no futuro)
     */
    record OpenRequestCommand(String type, String channel, String requesterName, String document,
                              String requesterContact, String details, Instant receivedAt) {
    }

    record RequestFilter(String status, String type, UUID debtorId, String document, Boolean overdue) {
    }

    /**
     * @param maskedDocument documento do requerente mascarado
     * @param maskedContact  contato do requerente mascarado
     * @param executedAt     quando a ação automática foi executada
     */
    record RequestView(UUID id, String type, String channel, String status, UUID debtorId, String requesterName,
                       String maskedDocument, String maskedContact, String details, Instant receivedAt, Instant dueAt,
                       boolean overdue, UUID assignedTo, Instant identityVerifiedAt, UUID identityVerifiedBy,
                       Instant executedAt, Instant completedAt, String responseSummary, boolean hasResultFile,
                       String resultSha256) {
    }
}
