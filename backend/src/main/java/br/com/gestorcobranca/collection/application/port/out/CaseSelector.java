package br.com.gestorcobranca.collection.application.port.out;

import br.com.gestorcobranca.collection.domain.model.CaseOrdering;
import br.com.gestorcobranca.collection.domain.model.PotentialBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seleção concorrente de casos para trabalho ("próximo caso" e distribuição em lote). As linhas
 * escolhidas ficam bloqueadas ({@code FOR UPDATE SKIP LOCKED}) até o fim da transação corrente:
 * dois operadores pedindo o próximo caso ao mesmo tempo nunca recebem o mesmo caso, e nenhum
 * espera pelo outro.
 * <p>
 * Disponível = na fila, ativo e trabalhável (OPEN, IN_NEGOTIATION, PROMISE), elegível, sem
 * operador, com dono {@code QUEUE} (não está com o assistente) e sem próxima ação futura. Casos
 * com retorno da fila já vencido vêm primeiro; depois a ordem da fila.
 *
 * @see CaseOrdering
 */
public interface CaseSelector {

    /**
     * @param allowedCreditorIds credores que o operador pode atender; nulo = todos
     */
    Optional<UUID> lockNextAvailable(UUID queueId, CaseOrdering ordering, Collection<UUID> allowedCreditorIds, Instant now);

    /** Até {@code limit} casos disponíveis da fila, na ordem de entrega, bloqueados para distribuição. */
    List<Candidate> lockAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit);

    /** Como {@link #lockAvailable}, sem bloquear (prévia da distribuição, em transação somente leitura). */
    List<Candidate> listAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit);

    /**
     * Distribuição por nível de desempenho: até {@code limit} casos disponíveis da fila em ordem de valor esperado
     * (probabilidade do score × saldo elegível; sem score, a probabilidade média dos disponíveis com score), com a
     * faixa de potencial por tercis calculada sobre <strong>todos</strong> os disponíveis da fila (mesma regra de
     * {@code CaseDistributor#tercile}), bloqueados para distribuição.
     */
    List<RankedCandidate> lockAvailableByPotential(UUID queueId, Instant now, int limit);

    /** Como {@link #lockAvailableByPotential}, sem bloquear (prévia). */
    List<RankedCandidate> listAvailableByPotential(UUID queueId, Instant now, int limit);

    /**
     * "Próximo caso" numa fila por nível de desempenho: o primeiro caso disponível na ordem das faixas informada
     * (faixas calculadas sobre todos os disponíveis da fila); dentro da faixa, retornos da fila vencidos primeiro e
     * depois o maior valor esperado. Bloqueado com {@code SKIP LOCKED}.
     *
     * @param bands              ordem de preferência das faixas (HIGH, MEDIUM, LOW)
     * @param allowedCreditorIds credores que o operador pode atender; nulo = todos
     */
    Optional<RankedCandidate> lockNextByPotential(UUID queueId, List<PotentialBand> bands,
                                                  Collection<UUID> allowedCreditorIds, Instant now);

    /**
     * Caso do próprio operador com retorno vencido (próxima ação alcançada), o mais antigo primeiro,
     * bloqueado. Tem precedência sobre casos novos da fila.
     */
    Optional<UUID> lockOwnDue(UUID userId, Collection<UUID> allowedCreditorIds, Instant now);

    record Candidate(UUID caseId, UUID creditorId) {
    }

    /**
     * @param score           propensão 0–1000 (nulo = sem score)
     * @param eligibleBalance saldo elegível
     * @param band            faixa de potencial (HIGH, MEDIUM ou LOW) entre os disponíveis da fila
     */
    record RankedCandidate(UUID caseId, UUID creditorId, Integer score, BigDecimal eligibleBalance,
                           PotentialBand band) {
    }
}
