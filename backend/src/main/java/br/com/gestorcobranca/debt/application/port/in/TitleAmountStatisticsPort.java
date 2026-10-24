package br.com.gestorcobranca.debt.application.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>Porta interna (módulo ingestion), sem endpoint.</strong> Distribuição histórica do valor principal dos
 * títulos já carregados no credor, para a análise de "ticket médio" da conferência da importação
 * ({@code docs/tecnico/desenho/importacao.md} §4): estatística robusta em escala logarítmica calculada no banco, sem
 * expor títulos nem devedores. Exige o credor no escopo do ator.
 */
public interface TitleAmountStatisticsPort {

    /**
     * @param products produtos de interesse (até 50); os demais não são calculados
     */
    AmountStatistics statistics(UUID creditorId, UUID portfolioId, Collection<String> products);

    /**
     * @param size         títulos considerados (principal > 0, qualquer situação)
     * @param medianLog    mediana de {@code ln(principal)}
     * @param madLog       mediana dos desvios absolutos de {@code ln(principal)}
     * @param integerShare fração dos títulos com principal sem centavos
     */
    record Distribution(long size, double medianLog, double madLog, double integerShare) {

        public static final Distribution EMPTY = new Distribution(0, 0, 0, 0);
    }

    /**
     * @param creditor  todos os títulos do credor
     * @param portfolio títulos do credor na carteira
     * @param byProduct títulos do credor na carteira, por produto do contrato de origem
     */
    record AmountStatistics(Distribution creditor, Distribution portfolio, Map<String, Distribution> byProduct) {
    }
}
