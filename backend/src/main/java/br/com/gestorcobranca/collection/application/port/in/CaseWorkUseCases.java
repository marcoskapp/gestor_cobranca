package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Trabalho dos casos (F07): "próximo caso", atribuição manual, distribuição em lote, devolução à
 * fila, redistribuição e dono do atendimento compartilhado com o assistente de IA e a caixa de
 * entrada (mesma semântica de {@code docs/design/comunicacao.md} §2: {@code ASSISTANT},
 * {@code QUEUE}, {@code USER}, {@code NONE}). Toda mudança de dono encerra o período vigente do
 * histórico de atribuição e abre outro, registra {@code case.assigned} e publica
 * {@code CaseAssignmentChanged} quando muda o responsável humano ou a equipe.
 */
public interface CaseWorkUseCases {

    /**
     * Entrega ao operador corrente o próximo caso: primeiro um caso dele com retorno vencido;
     * senão o primeiro caso disponível da fila informada (ou, sem fila, das filas atendidas pelas
     * suas equipes, em ordem de prioridade), bloqueado com {@code FOR UPDATE SKIP LOCKED}.
     * Respeita o escopo de credores e a capacidade por operador da fila.
     *
     * @return vazio quando não há caso disponível
     */
    Optional<CaseView> nextCase(UUID queueId);

    /** Casos ativos do operador corrente. */
    PageResponse<CaseView> myCases(PageQuery page);

    /**
     * Atribuição manual a um operador (com acesso ao credor do caso e permissão {@code CASE_WORK})
     * ou a uma equipe (sem operador).
     */
    CaseView assign(UUID caseId, AssignCommand command);

    /** Devolve o caso à fila (o próprio operador, ou quem tem {@code CASE_ASSIGN}). */
    CaseView release(UUID caseId, String reason);

    /** O operador corrente assume o caso (que estava na fila, sem dono ou com o assistente). */
    CaseView takeOver(UUID caseId, String reason);

    /** Entrega o atendimento ao assistente de IA (sem operador). */
    CaseView handToAssistant(UUID caseId, String reason);

    /**
     * Distribui os casos disponíveis da fila entre os membros (estratégia da fila: rodízio ou
     * menor carga), até a capacidade de cada um e respeitando o escopo de credores.
     *
     * @param limit máximo de casos nesta execução (1 a 1000)
     */
    DistributionResult distribute(UUID queueId, int limit);

    /**
     * Redistribui os casos ativos de um operador (ausência, desligamento): entre os operadores
     * informados pela estratégia de menor carga, ou de volta às filas quando nenhum for informado.
     */
    DistributionResult redistribute(RedistributeCommand command);

    PageResponse<AssignmentView> assignmentHistory(UUID caseId, PageQuery page);

    /** @param userId operador; nulo com {@code teamId} = atribuir à equipe */
    record AssignCommand(UUID userId, UUID teamId, String reason) {
    }

    /** @param toUserIds vazio = devolver às filas */
    record RedistributeCommand(UUID fromUserId, Set<UUID> toUserIds, String reason) {

        public RedistributeCommand {
            toUserIds = toUserIds == null ? Set.of() : Set.copyOf(toUserIds);
        }
    }

    /** @param unassigned casos sem operador possível (escopo ou capacidade) */
    record DistributionResult(int assigned, int unassigned, List<Assignment> assignments) {
    }

    record Assignment(UUID caseId, UUID userId) {
    }

    /**
     * Período de dono do atendimento.
     *
     * @param mode   OPENED, ROUTING, NEXT_CASE, MANUAL, DISTRIBUTION, REDISTRIBUTION, RELEASE,
     *               INTERACTION, ASSISTANT_HANDOFF, TAKE_OVER ou CLOSED
     * @param validTo nulo no período vigente
     */
    record AssignmentView(UUID id, UUID caseId, String mode, String ownerType, UUID userId, UUID teamId, UUID queueId,
                          String previousOwnerType, UUID previousUserId, String reason, Instant validFrom,
                          Instant validTo, UUID createdBy) {
    }
}
