package br.com.gestorcobranca.analytics.application.port.in;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.DatasetFreshness;

import java.util.List;

/** Atualização dos snapshots da assessoria corrente (job agendado ou pedido manual). */
public interface AnalyticsRefreshUseCases {

    /** Pedido manual (respeita o intervalo mínimo entre atualizações). */
    List<DatasetFreshness> refreshNow();
}
