package br.com.gestorcobranca.analytics.domain.service;

import br.com.gestorcobranca.analytics.domain.model.Ratio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Regras dos indicadores do portal do credor (docs/negocio/indicadores.md §10; decisões do responsável de 26/11/2026).
 * Puras: o SQL só soma por credor, mês e carteira.
 */
public final class CreditorIndicatorRules {

    /** Abaixo disto o cartão "Acordos em dia" fica em alerta (o texto diz o motivo). */
    public static final BigDecimal ON_TRACK_ALERT_BELOW = new BigDecimal("0.80");
    /** Carteiras mostradas uma a uma no "Recuperado por carteira"; as demais somam em "Outros". */
    public static final int TOP_PORTFOLIOS = 5;

    private CreditorIndicatorRules() {
    }

    /**
     * Acordo ativo em dia na data de corte: sem parcela vencida e não paga — ou com a quebra suspensa (pagamento
     * informado em análise: a assessoria ainda vai confirmar se a parcela foi paga, e o acordo não quebra enquanto isso).
     */
    public static boolean onTrack(boolean hasOverdueInstallment, boolean breakSuspended) {
        return !hasOverdueInstallment || breakSuspended;
    }

    /** Acordos em dia ÷ acordos ativos; sem acordo ativo, indisponível ({@code no_denominator}), nunca 0% nem 100%. */
    public static Ratio onTrackRatio(long onTrack, long active) {
        return Indicators.ratio(onTrack, active);
    }

    /** Em alerta abaixo de 80%; sem denominador não há alerta. */
    public static boolean onTrackAlert(Ratio ratio) {
        return ratio.value() != null && ratio.value().compareTo(ON_TRACK_ALERT_BELOW) < 0;
    }

    /**
     * As {@value #TOP_PORTFOLIOS} carteiras com mais recuperado (desempate pelo nome) e "Outros" com a soma das demais e
     * do recuperado sem carteira; "Outros" só aparece com valor diferente de zero. Carteira com recuperado zero fica
     * fora.
     */
    public static List<PortfolioShare> topWithOthers(List<PortfolioShare> shares) {
        List<PortfolioShare> named = shares.stream()
                .filter(s -> s.portfolioId() != null && s.recovered().signum() != 0)
                .sorted(Comparator.comparing(PortfolioShare::recovered).reversed()
                        .thenComparing(PortfolioShare::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<PortfolioShare> result = new ArrayList<>(named.subList(0, Math.min(TOP_PORTFOLIOS, named.size())));
        BigDecimal others = named.stream().skip(TOP_PORTFOLIOS).map(PortfolioShare::recovered)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(shares.stream().filter(s -> s.portfolioId() == null).map(PortfolioShare::recovered)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (others.signum() != 0) {
            result.add(new PortfolioShare(null, null, others.setScale(2, java.math.RoundingMode.HALF_EVEN)));
        }
        return List.copyOf(result);
    }

    /** @param portfolioId nulo = sem carteira (na entrada) ou "Outros" (na saída) */
    public record PortfolioShare(UUID portfolioId, String name, BigDecimal recovered) {
    }
}
