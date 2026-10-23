package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Casos de cobrança (F06): consulta, ficha agregada e ciclo de vida manual. A abertura, a
 * atualização da posição e o encerramento automático acontecem pelos eventos do módulo debt
 * ({@code TitleStatusChanged}, {@code PortfolioLoaded}); ver {@code docs/tecnico/desenho/collection.md}.
 * <p>
 * Escopo: leitura exige o credor do caso no escopo (404 fora dele); comandos, o credor no escopo
 * (403). Mudanças de status são auditadas, registram evento de negócio e publicam
 * {@code CaseStatusChanged}.
 */
public interface CaseUseCases {

    CaseView get(UUID caseId);

    PageResponse<CaseView> search(CaseFilter filter, PageQuery page);

    /** Casos (ativos e encerrados) do devedor nos credores do escopo, do mais recente ao mais antigo. */
    List<CaseView> listByDebtor(UUID debtorId);

    /** Caso ativo do devedor na carteira, se houver (uso por negotiation, communication, telephony…). */
    Optional<CaseView> findActive(UUID debtorId, UUID portfolioId);

    /**
     * Ficha do caso: posição e elegibilidade, devedor (dados mascarados), títulos da carteira,
     * restrições de contato vigentes, contestações abertas, promessa pendente, tarefas em aberto,
     * últimas interações e demais casos do devedor no escopo.
     */
    CaseSheet sheet(UUID caseId);

    /** Suspende a cobrança do caso (pedido do credor, análise). Exige motivo. */
    CaseView suspend(UUID caseId, String reason);

    /** Retoma um caso suspenso (volta ao estágio anterior, ou a DISPUTED se houver contestação bloqueante). */
    CaseView resume(UUID caseId, String reason);

    /** Encaminha ao jurídico: o caso sai da operação regular até retornar. */
    CaseView sendToLegal(UUID caseId, String reason);

    CaseView returnFromLegal(UUID caseId, String reason);

    /** Encerramento manual (incobrável, falecimento…) mesmo com títulos em aberto. */
    CaseView close(UUID caseId, String reason);

    /** Ressincroniza a posição dos títulos, a elegibilidade e a fila do caso. */
    CaseView refresh(UUID caseId);

    /**
     * @param statuses       OPEN, IN_NEGOTIATION, PROMISE, AGREEMENT, DISPUTED, SUSPENDED, LEGAL, CLOSED
     * @param ownerType      ASSISTANT, QUEUE, USER ou NONE
     * @param assigneeUserId responsável; use {@code me} na API para o próprio usuário
     */
    record CaseFilter(UUID creditorId, UUID portfolioId, UUID debtorId, Set<String> statuses, UUID queueId,
                      UUID assigneeUserId, String ownerType, Boolean eligible) {
    }

    /**
     * @param eligibilityReasons motivos de inelegibilidade (RB02) persistidos na última avaliação
     * @param contactableChannels canais sem restrição para o devedor/credor (janela e limites são
     *                            verificados a cada contato pelo porteiro do compliance)
     * @param daysOverdue        atraso do título mais antigo em cobrança, hoje (fuso de operação)
     */
    record CaseView(UUID id, UUID debtorId, UUID creditorId, UUID portfolioId, String status, String resumeStatus,
                    String statusReason, Instant openedAt, Instant closedAt, String closeReason, BigDecimal openBalance,
                    BigDecimal eligibleBalance, int openTitles, int suspendedTitles, int disputedTitles,
                    LocalDate oldestDueDate, long daysOverdue, boolean eligible, List<String> eligibilityReasons,
                    List<String> contactableChannels, Instant eligibilityEvaluatedAt, UUID queueId, String ownerType,
                    UUID assigneeUserId, UUID assigneeTeamId, Instant assignedAt, Instant nextActionAt,
                    Instant lastInteractionAt, Instant lastEffectiveContactAt, int interactionCount,
                    int effectiveContactCount, Integer score, long version) {
    }

    record CaseSheet(CaseView collectionCase, DebtorInfo debtor, List<TitleInfo> titles,
                     List<RestrictionInfo> activeRestrictions, List<DisputeUseCases.DisputeView> openDisputes,
                     PromiseUseCases.PromiseView pendingPromise, List<TaskUseCases.TaskView> openTasks,
                     List<InteractionUseCases.InteractionView> recentInteractions, List<CaseView> otherCases) {
    }

    /** Devedor com identificação e contatos sempre mascarados. */
    record DebtorInfo(UUID id, String name, String maskedDocument, String personType, String status,
                      List<ContactInfo> contacts) {
    }

    record ContactInfo(UUID contactId, String type, String maskedValue, String label, boolean whatsapp, String status,
                       int priority) {
    }

    record TitleInfo(UUID titleId, String externalId, Integer installmentNumber, LocalDate dueDate,
                     BigDecimal principalAmount, BigDecimal openBalance, String status, boolean disputeBlocked,
                     long daysOverdue) {
    }

    /** @param channel nulo = todos os canais; @param creditorId nulo = todos os credores */
    record RestrictionInfo(UUID id, String type, String channel, UUID creditorId, String contactMasked,
                           Instant startsAt, Instant endsAt) {
    }
}
