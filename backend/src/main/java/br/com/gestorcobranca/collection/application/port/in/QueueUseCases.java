package br.com.gestorcobranca.collection.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Filas de cobrança configuráveis (F07). Um caso pertence à primeira fila ativa, em ordem de
 * prioridade, cujos critérios satisfaz (credor, carteira, faixa de atraso, faixa de saldo, faixa
 * de score). Criar, alterar, ativar ou desativar uma fila reprocessa o roteamento dos casos
 * ativos em segundo plano. Fila de um credor exige o credor no escopo; fila sem credor exige
 * acesso a todos os credores.
 */
public interface QueueUseCases {

    QueueView create(QueueCommand command);

    QueueView update(UUID queueId, QueueCommand command);

    QueueView activate(UUID queueId);

    QueueView deactivate(UUID queueId);

    QueueView get(UUID queueId);

    /** Filas visíveis ao ator (sem credor, ou de credor do escopo), em ordem de prioridade. */
    List<QueueView> list();

    /** Contagens operacionais da fila (disponíveis, atribuídos por operador). */
    QueueSummary summary(UUID queueId);

    /** Reprocessa o roteamento de todos os casos ativos (em segundo plano). Exige acesso a todos os credores. */
    void reroute();

    /**
     * @param code            identificador estável (somente na criação; ignorado na alteração)
     * @param ordering        OLDEST_OVERDUE, HIGHEST_BALANCE, LOWEST_BALANCE, LEAST_RECENTLY_WORKED ou HIGHEST_SCORE
     * @param strategy        PULL, ROUND_ROBIN ou LEAST_LOADED
     * @param maxCasesPerUser capacidade de casos ativos por operador na fila (nulo = ilimitada)
     * @param teamIds         equipes que atendem a fila (vazio = qualquer operador)
     */
    record QueueCommand(String code, String name, String description, int priority, UUID creditorId, UUID portfolioId,
                        Integer minDaysOverdue, Integer maxDaysOverdue, BigDecimal minBalance, BigDecimal maxBalance,
                        Integer minScore, Integer maxScore, String ordering, String strategy, Integer maxCasesPerUser,
                        Set<UUID> teamIds) {

        public QueueCommand {
            teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        }
    }

    record QueueView(UUID id, String code, String name, String description, boolean active, int priority,
                     UUID creditorId, UUID portfolioId, Integer minDaysOverdue, Integer maxDaysOverdue,
                     BigDecimal minBalance, BigDecimal maxBalance, Integer minScore, Integer maxScore, String ordering,
                     String strategy, Integer maxCasesPerUser, Set<UUID> teamIds, Instant updatedAt, long version) {
    }

    /**
     * @param available casos disponíveis para "próximo caso" agora
     * @param waiting   casos na fila sem operador (inclui inelegíveis e com retorno futuro)
     */
    record QueueSummary(UUID queueId, long available, long waiting, long assigned, List<UserLoad> loads) {
    }

    record UserLoad(UUID userId, long activeCases) {
    }
}
