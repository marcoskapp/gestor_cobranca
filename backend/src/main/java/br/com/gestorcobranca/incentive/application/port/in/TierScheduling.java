package br.com.gestorcobranca.incentive.application.port.in;

/**
 * Rotina da classificação de desempenho (agendamento diário com ShedLock, ator de sistema no tenant), em duas etapas
 * chamadas em transações separadas: {@link #expireOverdueRound()} expira a rodada em revisão cujo prazo terminou (nada
 * muda: vale a publicação anterior) e {@link #openScheduledRound()} abre a rodada automática devida no período (mensal:
 * a partir do 1º dia útil do mês; semanal: a partir da segunda-feira) se ainda não houver rodada automática criada desde
 * o início do período nem outra em revisão. Ambas idempotentes.
 */
public interface TierScheduling {

    void expireOverdueRound();

    void openScheduledRound();
}
