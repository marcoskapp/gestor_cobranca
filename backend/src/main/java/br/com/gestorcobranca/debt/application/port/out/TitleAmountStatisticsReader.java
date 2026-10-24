package br.com.gestorcobranca.debt.application.port.out;

import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort.Distribution;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Leitura agregada da distribuição de valores principais (SQL). */
public interface TitleAmountStatisticsReader {

    /** @param portfolioId nulo = todas as carteiras do credor */
    Distribution distribution(UUID creditorId, UUID portfolioId);

    Map<String, Distribution> byProduct(UUID creditorId, UUID portfolioId, Collection<String> products);
}
