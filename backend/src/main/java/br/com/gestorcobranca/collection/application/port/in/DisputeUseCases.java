package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contestações (F21) — ver {@code docs/negocio/contestacoes.md}. Abrir uma contestação bloqueante vincula os títulos
 * no módulo debt (fora de réguas e negociações), coloca o caso em {@code DISPUTED} e suspende o contato de cobrança
 * (elegibilidade {@code DISPUTE_OPEN}) até a resolução ou desistência. Evidências vão para o armazenamento de
 * evidências (categoria EVIDENCE) com SHA-256. Publica {@code DisputeStatusChanged}.
 * <p>
 * Rotina: responsável (atribuir, assumir, redistribuir, distribuição automática opcional); prazo de resposta ao
 * devedor e prazo interno fixados na abertura pela hierarquia credor + tipo → credor → tipo → padrão; pedido formal
 * ao credor por e-mail (modelo por tipo, contatos de contestação do cadastro do credor, link seguro das evidências,
 * lembrete e cobrança do retorno vencido); resposta do credor (manual com documentos ou API pública); resolução com
 * ajuste sugerido no título; resposta fundamentada ao devedor (mensagem transacional pelo porteiro de contato).
 * <p>
 * Permissões: {@code DISPUTE_MANAGE} trabalha qualquer contestação do escopo e atribui; {@code DISPUTE_WORK}
 * trabalha as próprias e assume as sem responsável; abrir também com {@code CASE_WORK} (no caso que pode trabalhar);
 * a resposta do credor também pelo ator de integração do próprio credor (API pública, {@code disputes:write}).
 */
public interface DisputeUseCases {

    DisputeView open(UUID caseId, OpenDisputeCommand command);

    /** Encaminha para análise (credor / jurídico). */
    DisputeView startReview(UUID disputeId);

    /** @param outcome UPHELD, REJECTED ou PARTIALLY_UPHELD */
    default DisputeView resolve(UUID disputeId, String outcome, String note) {
        return resolve(disputeId, new ResolveCommand(outcome, note, null));
    }

    /** Resolve; procedente ou parcial sugere a ação no título (pendência até ser feita ou dispensada). */
    DisputeView resolve(UUID disputeId, ResolveCommand command);

    DisputeView withdraw(UUID disputeId, String note);

    /** PDF, imagem, áudio ou texto até o tamanho máximo configurado; até 20 por contestação. */
    EvidenceView attachEvidence(UUID disputeId, EvidenceUpload upload);

    List<EvidenceView> listEvidence(UUID disputeId);

    /** Abre a evidência para download; registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    EvidenceContent openEvidence(UUID disputeId, UUID evidenceId);

    DisputeView get(UUID disputeId);

    /** Contestação com o histórico: atribuições, pedidos ao credor (e e-mails), respostas do credor e respostas ao devedor. */
    DisputeDetail detail(UUID disputeId);

    List<DisputeView> listByCase(UUID caseId);

    /** Sincronização incremental (API pública): contestações do credor alteradas na janela, paginadas por cursor. */
    ChangePage<DisputeView> changes(UUID creditorId, ChangeQuery query);

    /** Fila de contestações, por prazo (o mais próximo entre o interno e o de resposta) e id. */
    PageResponse<DisputeView> search(DisputeQuery query, PageQuery page);

    /** Compatibilidade: filtros por credor, situação e vencimento. */
    default PageResponse<DisputeView> search(UUID creditorId, Set<String> statuses, Boolean overdue, PageQuery page) {
        return search(new DisputeQuery(creditorId, statuses, null, null, null, false, false, overdue, null, null, false,
                false, false), page);
    }

    // ------------------------------------------------------------------ responsável

    /** Atribui a um analista ({@code DISPUTE_MANAGE}); {@code assigneeId} nulo devolve à fila sem responsável. */
    DisputeView assign(UUID disputeId, UUID assigneeId, String reason);

    /** "Pegar para mim": contestação sem responsável, por quem trabalha contestações. */
    DisputeView take(UUID disputeId);

    /**
     * Redistribui as contestações abertas de um analista ({@code DISPUTE_MANAGE}): para outro analista, ou, sem destino,
     * para o analista elegível com menos contestações abertas (sem elegível, ficam sem responsável).
     */
    RedistributionResult redistribute(UUID fromAssigneeId, UUID toAssigneeId, String reason);

    /** Analistas que podem receber a contestação (ativos, com permissão e acesso ao credor), com a carga atual. */
    List<AssigneeOption> assigneeOptions(UUID disputeId);

    // ------------------------------------------------------------------ credor

    /** Pedido pré-preenchido: modelo do tipo, destinatários do cadastro do credor, títulos, evidências e prazo padrão. */
    CreditorRequestDraft draftCreditorRequest(UUID disputeId);

    /** Envia o pedido formal ao credor (e-mail assíncrono com retentativas); a contestação passa a aguardar o credor. */
    CreditorRequestView requestCreditor(UUID disputeId, CreditorRequestCommand command);

    /** Registra a resposta do credor (assessoria, com documentos; ou o credor, pela API pública). */
    CreditorResponseView registerCreditorResponse(UUID disputeId, CreditorResponseCommand command,
                                                  List<EvidenceUpload> documents);

    /** Pedidos ao credor da contestação (API pública: só os do próprio credor). */
    List<CreditorRequestView> creditorRequests(UUID disputeId);

    // ------------------------------------------------------------------ devedor

    /** Resposta pré-preenchida pelo modelo do resultado e canais permitidos agora (porteiro, categoria transacional). */
    DebtorNoticeDraft draftDebtorNotice(UUID disputeId);

    /** Envia (ou registra por outro meio) a resposta fundamentada ao devedor. */
    DebtorNoticeView notifyDebtor(UUID disputeId, DebtorNoticeCommand command);

    // ------------------------------------------------------------------ ajuste no título

    /** Dispensa o ajuste sugerido, com motivo ({@code DISPUTE_MANAGE}). */
    DisputeView dismissAdjustment(UUID disputeId, String reason);

    // ================================================================== registros

    /**
     * @param titleIds         títulos do caso contestados (vazio = todos os títulos em aberto do caso)
     * @param type             NOT_RECOGNIZED, ALREADY_PAID, WRONG_AMOUNT, FRAUD, PRESCRIBED,
     *                         PRODUCT_OR_SERVICE ou OTHER
     * @param origin           OPERATOR, DEBTOR, CREDITOR ou LEGAL
     * @param blocksCollection suspende a cobrança dos títulos e o contato (padrão: sim)
     * @param responseDueDate  prazo de resposta (nulo = pelas regras de prazo da assessoria)
     */
    record OpenDisputeCommand(Set<UUID> titleIds, String type, String origin, String description,
                              Boolean blocksCollection, LocalDate responseDueDate) {

        public OpenDisputeCommand {
            titleIds = titleIds == null ? Set.of() : Set.copyOf(titleIds);
        }
    }

    /**
     * @param adjustmentAction ação no título: nula = a sugerida pelo tipo; NONE = nenhuma; CANCEL_TITLES,
     *                         ADJUST_AMOUNT ou SETTLE_BY_PAYMENT
     */
    record ResolveCommand(String outcome, String note, String adjustmentAction) {
    }

    /**
     * Filtros da fila. Nulos/falsos = sem filtro.
     *
     * @param assignee   {@code me}, id do analista ou nulo
     * @param unassigned só sem responsável
     * @param dueUntil   prazo (interno ou de resposta) até a data
     * @param supervision visão do supervisor: sem responsável ou com prazo vencido
     */
    record DisputeQuery(UUID creditorId, Set<String> statuses, Set<String> types, Set<String> origins, String assignee,
                        boolean unassigned, boolean mine, Boolean overdue, LocalDate dueUntil, Boolean awaitingCreditor,
                        boolean noticePending, boolean adjustmentPending, boolean supervision) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    /** O chamador fecha o stream. @param size -1 se desconhecido */
    record EvidenceContent(String fileName, String contentType, long size, InputStream content) {
    }

    /**
     * @param titles             dados mínimos dos títulos contestados (número, parcela, vencimento, valores), na ordem
     *                           de vencimento; títulos que deixaram o escopo do ator não aparecem (continuam em
     *                           {@code titleIds})
     * @param internalDueDate    prazo interno (SLA da equipe), nunca depois do prazo de resposta
     * @param deadlineSource     INFORMED, CREDITOR_TYPE, CREDITOR, TYPE ou TENANT_DEFAULT
     * @param awaitingCreditorSince desde quando aguarda o retorno do credor (nulo = não aguarda)
     * @param debtorNoticeStatus PENDING, SENT ou NOT_REQUIRED (nulo enquanto aberta)
     * @param adjustmentAction   ação sugerida no título (CANCEL_TITLES, ADJUST_AMOUNT, SETTLE_BY_PAYMENT) ou nulo
     * @param adjustmentStatus   PENDING, DONE ou DISMISSED
     */
    record DisputeView(UUID id, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, Set<UUID> titleIds,
                       List<DisputedTitle> titles, String type, String origin, String status, String description,
                       boolean blocksCollection, BigDecimal disputedAmount, Instant openedAt, LocalDate responseDueDate,
                       boolean overdue, String outcome, String resolutionNote, Instant resolvedAt, UUID resolvedBy,
                       int evidenceCount, UUID assigneeId, String assigneeName, Instant assignedAt,
                       LocalDate internalDueDate, String deadlineSource, Instant awaitingCreditorSince,
                       String debtorNoticeStatus, String adjustmentAction, String adjustmentStatus,
                       BigDecimal adjustmentBaseline, String adjustmentNote, Instant adjustmentClosedAt, long version) {
    }

    /**
     * @param externalId      número do título no credor
     * @param principalAmount valor original
     * @param openBalance     saldo em aberto atual
     * @param status          situação atual do título (OPEN, SUSPENDED, SETTLED, CANCELLED…)
     */
    record DisputedTitle(UUID titleId, String externalId, Integer installmentNumber, LocalDate dueDate,
                         BigDecimal principalAmount, BigDecimal openBalance, String status) {
    }

    /**
     * @param createdByName        nome de exibição de quem anexou (nulo para atores de sistema/integração)
     * @param creditorResponseId   resposta do credor que trouxe o documento (nulo = anexado pela assessoria)
     */
    record EvidenceView(UUID id, UUID disputeId, String fileName, String contentType, long size, String sha256,
                        Instant createdAt, UUID createdBy, String createdByName, UUID creditorResponseId) {
    }

    record DisputeDetail(DisputeView dispute, List<AssignmentView> assignments, List<CreditorRequestView> creditorRequests,
                         List<CreditorResponseView> creditorResponses, List<DebtorNoticeView> debtorNotices) {
    }

    /** @param mode MANUAL, TAKE, AUTOMATIC, REDISTRIBUTION ou RELEASE */
    record AssignmentView(UUID id, UUID previousAssigneeId, String previousAssigneeName, UUID assigneeId,
                          String assigneeName, String mode, String reason, UUID assignedBy, String assignedByName,
                          Instant assignedAt) {
    }

    record RedistributionResult(int moved, int unassigned) {
    }

    /** @param openDisputes contestações abertas do analista hoje */
    record AssigneeOption(UUID userId, String name, long openDisputes) {
    }

    // ------------------------------------------------------------------ credor

    /**
     * @param to              e-mails dos contatos principais de contestação do credor (ou da carteira)
     * @param cc              cópias do cadastro do credor
     * @param replyTo         e-mail do responsável (as respostas do credor vão para ele)
     * @param templateVersion versão do modelo usada (0 = padrão do sistema)
     * @param linkExpiresAt   validade do link das evidências se o pedido for enviado agora
     */
    record CreditorRequestDraft(String templateKey, int templateVersion, String subject, String body,
                                List<String> to, List<String> cc, String replyTo, boolean copyAssignee,
                                LocalDate requestedDueDate, List<DisputedTitle> titles, List<EvidenceView> evidences,
                                Instant linkExpiresAt, boolean recipientsFromPortfolio) {
    }

    /**
     * @param titleIds     títulos citados no pedido (vazio = todos os da contestação)
     * @param evidenceIds  evidências compartilhadas por link seguro com validade (vazio = nenhuma)
     * @param copyAssignee cópia ao responsável
     * @param subject      assunto já editado (sem variáveis por preencher)
     * @param body         texto já editado (sem variáveis por preencher; o link é acrescentado pelo sistema)
     */
    record CreditorRequestCommand(String subject, String body, LocalDate requestedDueDate, Set<UUID> titleIds,
                                  Set<UUID> evidenceIds, boolean copyAssignee) {

        public CreditorRequestCommand {
            titleIds = titleIds == null ? Set.of() : Set.copyOf(titleIds);
            evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
        }
    }

    /**
     * @param status QUEUED, SENT, FAILED, ANSWERED ou CANCELLED
     * @param mails  e-mails do pedido (pedido, lembrete, cobrança do retorno vencido), com a situação de cada um
     */
    record CreditorRequestView(UUID id, UUID disputeId, int sequenceNumber, String templateKey, int templateVersion,
                               List<String> to, List<String> cc, String replyTo, String subject, String body,
                               LocalDate requestedDueDate, String status, boolean overdue, Instant sentAt,
                               Instant answeredAt, Instant linkExpiresAt, int linkDownloads, Set<UUID> titleIds,
                               Set<UUID> evidenceIds, UUID requestedBy, String requestedByName, Instant createdAt,
                               List<MailView> mails) {
    }

    /** @param purpose REQUEST, REMINDER ou OVERDUE; @param status QUEUED, SENT ou FAILED */
    record MailView(UUID id, String purpose, String status, int attempts, Instant sentAt, Instant failedAt,
                    String failureCode) {
    }

    /**
     * @param position   CONFIRMS_DEBT, ACCEPTS_CLAIM, PARTIAL ou INFORMATION
     * @param requestId  pedido respondido (nulo = o pedido mais recente aguardando, se houver)
     * @param receivedAt quando o credor respondeu (nulo = agora; não pode ser futuro)
     */
    record CreditorResponseCommand(String position, String note, UUID requestId, Instant receivedAt) {
    }

    /** @param source MANUAL ou PUBLIC_API */
    record CreditorResponseView(UUID id, UUID disputeId, UUID requestId, String source, String position, String note,
                                Instant receivedAt, UUID recordedBy, String recordedByName, List<UUID> evidenceIds) {
    }

    // ------------------------------------------------------------------ devedor

    /**
     * @param channels canais permitidos agora pelo porteiro (categoria transacional) e os motivos dos demais
     */
    record DebtorNoticeDraft(String templateKey, int templateVersion, String subject, String body,
                             List<NoticeChannelOption> channels) {
    }

    /**
     * @param channel  EMAIL ou WHATSAPP
     * @param allowed  o porteiro permite agora
     * @param reasons  motivos da negação (opt-out, restrição, janela…)
     * @param nextAllowedAt próximo instante permitido (nulo se permitido ou sem fim previsto)
     * @param maskedContact contato que seria usado, mascarado (nulo = sem contato utilizável)
     */
    record NoticeChannelOption(String channel, boolean allowed, List<String> reasons, Instant nextAllowedAt,
                               UUID contactId, String maskedContact) {
    }

    /**
     * @param channel   EMAIL ou WHATSAPP (mensagem pelo porteiro) ou OTHER (registro de entrega por outro meio:
     *                  carta, presencial, protocolo do órgão — descrito em {@code body})
     * @param contactId contato do devedor (opcional; padrão = o principal do canal)
     */
    record DebtorNoticeCommand(String channel, UUID contactId, String subject, String body) {
    }

    /** @param status QUEUED, SENT, DELIVERED, READ, FAILED, BLOCKED ou REGISTERED */
    record DebtorNoticeView(UUID id, UUID disputeId, String channel, UUID messageId, String status,
                            String failureDetail, String subject, String body, UUID sentBy, String sentByName,
                            Instant createdAt, Instant statusAt) {
    }
}
