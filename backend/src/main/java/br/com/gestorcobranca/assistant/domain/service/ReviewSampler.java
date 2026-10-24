package br.com.gestorcobranca.assistant.domain.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Amostragem determinística para revisão humana: o mesmo turno sempre tem a mesma decisão (reprocessar
 * não muda a amostra) e a fração amostrada converge para a taxa da política.
 */
public final class ReviewSampler {

    private ReviewSampler() {
    }

    public static boolean sampled(UUID turnId, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return false;
        }
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            return true;
        }
        long bucket = Math.floorMod(turnId.getLeastSignificantBits() ^ turnId.getMostSignificantBits(), 10_000L);
        return bucket < rate.movePointRight(4).longValue();
    }
}
