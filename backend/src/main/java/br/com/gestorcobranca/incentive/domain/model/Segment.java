package br.com.gestorcobranca.incentive.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Segmento de dificuldade de um crédito: credor × carteira × faixa de atraso (§2.5).
 *
 * @param portfolioId pode ser nulo (pagamento sem carteira identificada)
 */
public record Segment(UUID creditorId, UUID portfolioId, DelayBucket delayBucket) implements Comparable<Segment> {

    public Segment {
        Objects.requireNonNull(creditorId, "creditorId");
        delayBucket = delayBucket == null ? DelayBucket.UNKNOWN : delayBucket;
    }

    /** Chave estável para memória de cálculo e ordenação: {@code credor/carteira/faixa}. */
    public String key() {
        return creditorId + "/" + (portfolioId == null ? "-" : portfolioId) + "/" + delayBucket.name();
    }

    @Override
    public int compareTo(Segment o) {
        return key().compareTo(o.key());
    }
}
