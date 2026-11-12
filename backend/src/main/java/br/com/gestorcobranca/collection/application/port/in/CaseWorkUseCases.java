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
 * entrada (mesma semântica de {@code docs/tecnico/desenho/comunicacao.md} §2: {@code ASSISTANT},
 * {@code QUEUE}, {@code USER}, {@code NONE}). Toda mudança de dono encerra o período vigente do
 * histórico de atribuição e abre outro, registra {@code case.assigned} e publica
 * {@code CaseAssignmentChanged} quando muda o responsável humano ou a equipe.
 */
public interface CaseWorkUseCases {

    /**
     * Entrega ao operador corrente o próximo caso: primeiro um caso dele com retorno vencido;
     * senão o primeiro caso disponível da fila informada (ou, sem fila, das filas atendidas pelas
     * suas equipes, em ordem de prioridade), bloqueado com {@code FOR UPDATE SKIP LOCKED}.
     * Respeita o escopo de credores e a capacidade por operador da fila. Em fila por nível de desempenho
     * ({@code TIERED}), entrega primeiro a faixa de potencial do nível do operador e respeita a capacidade
     * multiplicada pelo nível (distribuicao-por-desempenho.md §4.2).
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

    /**
     * Devolve o caso à fila: o próprio operador, quem tem {@code CASE_ASSIGN}, ou — para casos
     * conduzidos pelo assistente — o próprio assistente (transferência para humano).
     */
    CaseView release(UUID caseId, String reason);

    /** O operador corrente assume o caso (que estava na fila, sem dono ou com o assistente). */
    CaseView takeOver(UUID caseId, String reason);

    /** Entrega o atendimento ao assistente de IA (sem operador). */
    CaseView handToAssistant(UUID caseId, String reason);

    /**
     * Distribui os casos disponíveis da fila entre os membros (estratégia da fila: rodízio, menor
     * carga ou por nível de desempenho), até a capacidade de cada um e respeitando o escopo de credores.
     *
     * @param limit máximo de casos nesta execução (1 a 1000)
     */
    DistributionResult distribute(UUID queueId, int limit);

    /**
     * Prévia da distribuição, sem gravar nem bloquear casos: o mesmo plano do {@link #distribute} com os casos e as
     * cargas de agora. O nível de cada pessoa só vem para quem pode vê-lo ({@code PERFORMANCE_VIEW_TEAM/ALL} no
     * escopo, ou o próprio); sem isso, só as quantidades por nível e faixa.
     *
     * @param limit máximo de casos considerados (1 a 1000)
     */
    DistributionPreview previewDistribution(UUID queueId, int limit);

    /**
     * Redistribui os casos ativos de um operador (ausência, desligamento): entre os operadores
     * informados pela estratégia de menor carga, ou de volta às filas quando nenhum for informado.
     * Destino aceito: ativo, {@code CASE_WORK}, diferente da origem e com acesso a ao menos um credor do
     * escopo de quem redistribui (mesma regra de {@link CaseAssigneeUseCases.Purpose#CASE_REDISTRIBUTION_TARGET}).
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

    /**
     * @param unassigned  casos sem operador possível (escopo ou capacidade)
     * @param assignments em fila por nível, só os casos de pessoas cujo nível o ator pode ver (os totais são completos)
     */
    record DistributionResult(int assigned, int unassigned, List<Assignment> assignments) {
    }

    record Assignment(UUID caseId, UUID userId) {
    }

    /**
     * @param strategy    estratégia da fila
     * @param considered  casos disponíveis considerados (até o limite)
     * @param assigned    casos que iriam a um operador
     * @param unassigned  casos sem operador possível (escopo ou capacidade)
     * @param byTierBand  por nível × faixa de potencial (só {@code TIERED}; faixa {@code DEVELOPMENT} = fatia de
     *                    desenvolvimento). Com o nível só se o ator vê o nível de <b>todos</b> os membros da fila;
     *                    senão, só totais por faixa ({@code tier} nulo) — a contagem por nível somada ao planejado de
     *                    cada pessoa revelaria o nível de quem ele não vê
     * @param byTier      membros e casos planejados por nível (só {@code TIERED} e só com visão do nível de todos os
     *                    membros; senão vazio)
     * @param members     por operador: só id e quantidades; o nível e a capacidade efetiva (que revela o nível) só
     *                    para quem pode ver o nível da pessoa
     */
    record DistributionPreview(UUID queueId, String strategy, int considered, int assigned, int unassigned,
                               List<TierBandCount> byTierBand, List<TierCount> byTier, List<MemberPreview> members) {
    }

    /**
     * @param tier nível (A–D) ou nulo quando a prévia mostra só o total por faixa (ator sem visão do nível de todos)
     * @param band HIGH, MEDIUM, LOW ou DEVELOPMENT
     */
    record TierBandCount(String tier, String band, int cases) {
    }

    record TierCount(String tier, int members, int cases) {
    }

    /**
     * Pessoa na prévia. Em fila por nível, só as pessoas cujo nível o ator pode ver (o planejado revelaria o nível); as
     * demais entram apenas nos totais por faixa.
     *
     * @param tier     nível efetivo (nulo fora do {@code TIERED})
     * @param capacity capacidade efetiva (nulo = ilimitada)
     */
    record MemberPreview(UUID userId, String tier, int activeCases, Integer capacity, int planned) {
    }

    /**
     * Período de dono do atendimento.
     *
     * @param mode   OPENED, ROUTING, NEXT_CASE, MANUAL, DISTRIBUTION, REDISTRIBUTION, RELEASE,
     *               INTERACTION, ASSISTANT_HANDOFF, TAKE_OVER ou CLOSED
     * @param validTo nulo no período vigente
     * @param tier    nível de quem recebeu na distribuição por nível (nulo fora dela ou sem permissão para ver o
     *                nível da pessoa)
     * @param potentialBand faixa de potencial do caso na distribuição por nível: HIGH, MEDIUM, LOW ou DEVELOPMENT
     */
    record AssignmentView(UUID id, UUID caseId, String mode, String ownerType, UUID userId, UUID teamId, UUID queueId,
                          String previousOwnerType, UUID previousUserId, String reason, Instant validFrom,
                          Instant validTo, UUID createdBy, String tier, String potentialBand) {
    }
}
