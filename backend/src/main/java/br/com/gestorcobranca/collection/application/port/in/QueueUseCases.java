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
     * @param strategy        PULL, ROUND_ROBIN, LEAST_LOADED ou TIERED (por nível de desempenho; exige
     *                        {@code maxCasesPerUser})
     * @param maxCasesPerUser capacidade de casos ativos por operador na fila (nulo = ilimitada); em TIERED, a base
     *                        multiplicada pelo nível de cada operador
     * @param teamIds         equipes que atendem a fila (vazio = qualquer operador)
     * @param vulnerableOnly  só casos de devedor com marcador de vulnerabilidade (fila própria de atendimento
     *                        cuidadoso; nulo = não restringe)
     * @param newContactOnly  só casos de devedor com contato novo recebido na carga ainda sem tentativa
     *                        (contatos-recebidos.md §4; nulo = não restringe)
     * @param personType      só casos de devedor deste tipo de pessoa: INDIVIDUAL ou COMPANY (nulo = qualquer)
     * @param autoDistribution distribuição automática por horário (nulo = desligada)
     */
    record QueueCommand(String code, String name, String description, int priority, UUID creditorId, UUID portfolioId,
                        Integer minDaysOverdue, Integer maxDaysOverdue, BigDecimal minBalance, BigDecimal maxBalance,
                        Integer minScore, Integer maxScore, String ordering, String strategy, Integer maxCasesPerUser,
                        Set<UUID> teamIds, Boolean vulnerableOnly, Boolean newContactOnly, String personType,
                        AutoDistributionInput autoDistribution) {

        public QueueCommand {
            teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        }
    }

    /**
     * Distribuição automática da fila (distribuicao-por-desempenho.md §4.3): nos horários informados (fuso de
     * operação, dias úteis), uma rodada por horário por dia, com a mesma regra do "Distribuir". Fila sob demanda
     * ({@code PULL}) não aceita.
     *
     * @param enabled ligada
     * @param times   horários {@code HH:mm} (1 a 6 quando ligada)
     * @param limit   máximo de casos por rodada (1 a 1000; obrigatório quando ligada)
     */
    record AutoDistributionInput(Boolean enabled, List<String> times, Integer limit) {

        public AutoDistributionInput {
            times = times == null ? List.of() : List.copyOf(times);
        }
    }

    /** @param lastRunAt última rodada automática (ou última mudança da configuração automática) */
    record AutoDistributionView(boolean enabled, List<String> times, Integer limit, Instant lastRunAt) {
    }

    record QueueView(UUID id, String code, String name, String description, boolean active, int priority,
                     UUID creditorId, UUID portfolioId, Integer minDaysOverdue, Integer maxDaysOverdue,
                     BigDecimal minBalance, BigDecimal maxBalance, Integer minScore, Integer maxScore, String ordering,
                     String strategy, Integer maxCasesPerUser, Set<UUID> teamIds, Instant updatedAt, long version,
                     boolean vulnerableOnly, boolean newContactOnly, String personType,
                     AutoDistributionView autoDistribution) {
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
