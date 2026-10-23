package br.com.gestorcobranca.telephony.domain.service;

import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.telephony.domain.model.DialerMode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ritmo do discador: quantas chamadas novas colocar agora.
 * <ul>
 *   <li><b>Progressivo</b>: uma linha por operador livre — {@code livres − em andamento}.</li>
 *   <li><b>Preditivo</b>: {@code razão = 1 ÷ taxa de atendimento humano} (chamadas discadas por
 *       atendimento observado no dia), limitada à razão máxima da campanha. Com abandono
 *       observado ≥ 80% do máximo a razão cai pela metade do excedente; no máximo ou acima, volta
 *       a 1 (progressivo) até a taxa baixar. Sem amostra mínima (aquecimento), razão 1.</li>
 * </ul>
 * Abandono = atendidas por pessoa sem operador ÷ atendidas por pessoa (conectadas + abandonadas),
 * no dia de operação — critério de mercado (ex.: Ofcom/FTC, limite de 3%).
 */
public final class DialerPacing {

    /** Atendidas por pessoa necessárias para confiar na taxa de abandono observada. */
    public static final int MIN_LIVE_SAMPLE = 20;
    /** Discagens necessárias para estimar a taxa de atendimento. */
    public static final int MIN_DIAL_SAMPLE = 30;
    private static final BigDecimal NEAR_LIMIT = new BigDecimal("0.80");

    private DialerPacing() {
    }

    /**
     * @param availableAgents operadores disponíveis na campanha
     * @param callsInProgress chamadas da campanha ainda sem operador (na fila, discando, tocando)
     * @param liveCalls       atendidas por pessoa no dia (conectadas + abandonadas)
     * @param abandonedCalls  abandonadas no dia
     * @param dialedCalls     chamadas que chegaram ao provedor no dia
     * @param maxPerTick      teto de novas chamadas por ciclo
     */
    public record Input(DialerMode mode, int availableAgents, int callsInProgress, BigDecimal maxDialRatio,
                        Percentage maxAbandonRate, long liveCalls, long abandonedCalls, long dialedCalls, int maxPerTick) {
    }

    /**
     * @param reason {@code no_agents}, {@code progressive}, {@code warming_up}, {@code predictive},
     *               {@code abandon_near_limit}, {@code abandon_limit}, {@code lines_busy}
     */
    public record Decision(int callsToPlace, BigDecimal ratio, BigDecimal abandonRate, String reason) {
    }

    public static Decision decide(Input in) {
        BigDecimal abandonRate = abandonRatePercent(in.liveCalls(), in.abandonedCalls());
        if (in.availableAgents() <= 0) {
            return new Decision(0, BigDecimal.ZERO, abandonRate, "no_agents");
        }
        BigDecimal ratio;
        String reason;
        if (in.mode() == DialerMode.PROGRESSIVE) {
            ratio = BigDecimal.ONE;
            reason = "progressive";
        } else if (in.liveCalls() >= MIN_LIVE_SAMPLE && abandonRate.compareTo(in.maxAbandonRate().value()) >= 0) {
            ratio = BigDecimal.ONE;
            reason = "abandon_limit";
        } else if (in.dialedCalls() < MIN_DIAL_SAMPLE || in.liveCalls() == 0) {
            ratio = BigDecimal.ONE;
            reason = "warming_up";
        } else {
            BigDecimal answerRate = BigDecimal.valueOf(in.liveCalls())
                    .divide(BigDecimal.valueOf(in.dialedCalls()), 6, RoundingMode.HALF_EVEN);
            ratio = BigDecimal.ONE.divide(answerRate, 2, RoundingMode.DOWN).min(in.maxDialRatio()).max(BigDecimal.ONE);
            reason = "predictive";
            BigDecimal nearLimit = in.maxAbandonRate().value().multiply(NEAR_LIMIT);
            if (in.liveCalls() >= MIN_LIVE_SAMPLE && abandonRate.compareTo(nearLimit) >= 0) {
                ratio = BigDecimal.ONE.add(ratio.subtract(BigDecimal.ONE).divide(BigDecimal.TWO, 2, RoundingMode.DOWN));
                reason = "abandon_near_limit";
            }
        }
        int target = ratio.multiply(BigDecimal.valueOf(in.availableAgents())).setScale(0, RoundingMode.DOWN).intValue();
        int toPlace = Math.min(Math.max(0, target - in.callsInProgress()), Math.max(0, in.maxPerTick()));
        return new Decision(toPlace, ratio, abandonRate, toPlace == 0 ? "lines_busy" : reason);
    }

    /** Percentual de abandono (0–100, 2 casas); zero sem chamadas atendidas. */
    public static BigDecimal abandonRatePercent(long liveCalls, long abandonedCalls) {
        if (liveCalls <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(abandonedCalls).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(liveCalls), 2, RoundingMode.HALF_EVEN);
    }
}
