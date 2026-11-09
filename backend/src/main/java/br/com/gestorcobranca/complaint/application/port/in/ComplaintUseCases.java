package br.com.gestorcobranca.complaint.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reclamações de canais externos (I5 da revisão de produto de 11/2026) — ver
 * {@code docs/tecnico/desenho/conformidade.md} §3.
 * <p>
 * Permissões: {@code COMPLAINT_REGISTER} registra, consulta e anexa evidências; {@code COMPLAINT_MANAGE} também
 * atribui o responsável, registra a resposta, encerra, ajusta prazos e as regras por canal e vê o indicador por
 * credor. Escopo por credor em tudo (a reclamação é sempre de um credor); devedor e caso, se informados, precisam
 * estar visíveis ao ator e ser do mesmo credor. O resumo e a resposta (texto com dado pessoal) só saem no detalhe,
 * que registra {@code SENSITIVE_DATA_VIEWED}.
 */
public interface ComplaintUseCases {

    ComplaintView register(RegisterComplaintCommand command);

    /** Corrige devedor, caso, protocolo e assunto (antes do encerramento). */
    ComplaintView reclassify(UUID complaintId, ReclassifyCommand command);

    /** Prazo novo informado pelo canal (prorrogação). */
    ComplaintView changeDueDate(UUID complaintId, LocalDate dueDate, String reason);

    /** @param assigneeId nulo = sem responsável */
    ComplaintView assign(UUID complaintId, UUID assigneeId);

    /** Começa o tratamento (assume a reclamação se estiver sem responsável). */
    ComplaintView start(UUID complaintId);

    /** @param answeredAt quando a resposta foi enviada ao canal (nulo = agora; não pode ser futuro) */
    ComplaintView answer(UUID complaintId, String response, Instant answeredAt);

    /** @param outcome RESOLVED, NOT_ACCEPTED, UNFOUNDED ou CLOSED_BY_CHANNEL */
    ComplaintView close(UUID complaintId, String outcome);

    /** Detalhe com o resumo e a resposta; registra {@code SENSITIVE_DATA_VIEWED}. */
    ComplaintDetail detail(UUID complaintId);

    PageResponse<ComplaintView> search(ComplaintQuery query, PageQuery page);

    /** Pessoas que podem tratar a reclamação (ativas, com {@code COMPLAINT_MANAGE} e acesso ao credor). */
    List<AssigneeOption> assigneeOptions(UUID complaintId);

    // ------------------------------------------------------------------ evidências

    /** PDF, PNG ou JPEG (conferido pelo conteúdo), até {@value #MAX_EVIDENCE_MB} MB e 20 por reclamação. */
    EvidenceView attachEvidence(UUID complaintId, EvidenceUpload upload);

    List<EvidenceView> evidences(UUID complaintId);

    /** Abre a evidência liberada; registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    EvidenceContent openEvidence(UUID complaintId, UUID evidenceId);

    // ------------------------------------------------------------------ configuração e indicador

    /** Prazo por canal (os canais sem regra usam o padrão do sistema). */
    List<DeadlineRuleView> deadlineRules();

    DeadlineRuleView updateDeadlineRule(String channel, int responseDays, int warningDays);

    /**
     * Indicador por credor (docs/negocio/indicadores.md §9): recebidas no período, respondidas no prazo e fora do
     * prazo, e em aberto vencidas agora. Credores fora do escopo do ator não aparecem.
     */
    List<CreditorIndicator> indicatorByCreditor(LocalDate from, LocalDate to);

    int MAX_EVIDENCE_MB = 10;

    // ================================================================== registros

    /**
     * @param channel          PROCON, CONSUMIDOR_GOV, BACEN, CREDITOR_OMBUDSMAN, RECLAME_AQUI ou OTHER
     * @param subject          ABUSIVE_COLLECTION, CONTACT_FREQUENCY, THIRD_PARTY_CONTACT, DEBT_NOT_RECOGNIZED,
     *                         WRONG_AMOUNT, AGREEMENT_OR_PAYMENT, CREDIT_BUREAU, DATA_PRIVACY ou OTHER
     * @param receivedAt       quando a assessoria recebeu (nulo = agora; não pode ser futuro)
     * @param responseDueDate  prazo informado pelo canal (nulo = regra do canal)
     * @param assigneeId       responsável já na abertura (exige {@code COMPLAINT_MANAGE})
     */
    record RegisterComplaintCommand(UUID creditorId, UUID debtorId, UUID caseId, String channel,
                                    String externalProtocol, String subject, String summary, Instant receivedAt,
                                    LocalDate responseDueDate, UUID assigneeId) {
    }

    record ReclassifyCommand(UUID debtorId, UUID caseId, String externalProtocol, String subject) {
    }

    /**
     * Fila. Nulos/falsos = sem filtro.
     *
     * @param assignee {@code me}, id do responsável ou nulo
     * @param deadline {@code overdue} (vencidas), {@code soon} (vencem até o aviso do canal, inclusive vencidas),
     *                 {@code on_time} ou nulo
     */
    record ComplaintQuery(UUID creditorId, Set<String> statuses, Set<String> channels, UUID debtorId, UUID caseId,
                          String assignee, boolean unassigned, String deadline, String externalProtocol) {
    }

    /**
     * Linha da fila, sem o texto da reclamação nem da resposta (minimização).
     *
     * @param deadlineState ON_TIME, DUE_SOON, OVERDUE (aguardando resposta) ou ANSWERED_ON_TIME / ANSWERED_LATE
     * @param daysToDue     dias até o prazo (negativo = vencida; nulo depois de respondida)
     */
    record ComplaintView(UUID id, UUID creditorId, UUID debtorId, UUID caseId, String channel, String externalProtocol,
                         String subject, String status, Instant receivedAt, LocalDate dueDate, String deadlineSource,
                         String deadlineState, Long daysToDue, UUID assigneeId, String assigneeName, Instant answeredAt,
                         Boolean answeredOnTime, String outcome, Instant closedAt, int evidenceCount, long version) {
    }

    record ComplaintDetail(ComplaintView complaint, String summary, String response, UUID registeredBy,
                           String registeredByName, UUID answeredBy, String answeredByName) {
    }

    record AssigneeOption(UUID userId, String name, long openComplaints) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record EvidenceContent(String fileName, String contentType, long size, InputStream content) {
    }

    /** @param status PENDING_SCAN, AVAILABLE, REJECTED ou FAILED; @param verdict CLEAN, NOT_SCANNED ou INFECTED */
    record EvidenceView(UUID id, UUID complaintId, String fileName, String contentType, long size, String sha256,
                        String status, String verdict, Instant createdAt, UUID createdBy, String createdByName) {
    }

    /** @param defaultRule sem regra da assessoria: valores padrão do sistema */
    record DeadlineRuleView(String channel, int responseDays, int warningDays, boolean defaultRule) {
    }

    /**
     * @param received        recebidas no período
     * @param answeredOnTime  respondidas no período até o prazo
     * @param answeredLate    respondidas no período depois do prazo
     * @param openOverdue     aguardando resposta com prazo vencido agora
     * @param open            aguardando resposta agora
     */
    record CreditorIndicator(UUID creditorId, long received, long answeredOnTime, long answeredLate, long openOverdue,
                             long open) {
    }
}
