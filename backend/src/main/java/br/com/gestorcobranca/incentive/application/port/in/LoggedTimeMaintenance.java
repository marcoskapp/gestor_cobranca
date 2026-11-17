package br.com.gestorcobranca.incentive.application.port.in;

import java.time.LocalDate;

/**
 * Recalcula o tempo logado por dia (fatos do incentive, base da produtividade e da ociosidade da margem) a partir das
 * sessões do identity num período informado — a sincronização agendada ({@code IncentiveJobs#syncLoggedTime}) só cobre
 * ontem e hoje. Idempotente: os dias do período são refeitos por inteiro.
 * <p>
 * Só para o ator de sistema da assessoria (rotinas e a carga do sandbox de demonstração, que gera sessões fictícias no
 * histórico); qualquer outro ator recebe {@code IllegalStateException}. Não tem rota HTTP.
 */
public interface LoggedTimeMaintenance {

    /**
     * @param from primeiro dia (fuso de operação)
     * @param to   último dia, inclusive (até 400 dias depois de {@code from})
     * @return quantas pessoas tiveram tempo logado no período
     */
    int resyncLoggedTime(LocalDate from, LocalDate to);
}
