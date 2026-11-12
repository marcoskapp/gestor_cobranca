package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.NegotiatorTier;
import br.com.gestorcobranca.collection.domain.model.PerformanceTier;
import br.com.gestorcobranca.collection.domain.repository.NegotiatorTierRepository;
import br.com.gestorcobranca.shared.events.incentive.NegotiatorTierChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projeção {@code collection.negotiator_tier} do evento {@link NegotiatorTierChanged} (incentive). Idempotente pela
 * publicação: a mesma pessoa e publicação gravam uma única linha (reentregas com outro {@code eventId} também). O
 * nível corrente é lido pela vigência ({@code NegotiatorTierRepository#currentTiers}), então entregas fora de ordem
 * não voltam a um nível antigo. Nível nulo grava "sem nível" (vale o padrão da assessoria).
 */
@Component
class NegotiatorTierProjection {

    private static final Logger LOG = LoggerFactory.getLogger(NegotiatorTierProjection.class);

    private final NegotiatorTierRepository tiers;
    private final CollectionMetrics metrics;

    NegotiatorTierProjection(NegotiatorTierRepository tiers, CollectionMetrics metrics) {
        this.tiers = tiers;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void record(NegotiatorTierChanged e) {
        if (e.userId() == null || e.validFrom() == null || e.publicationId() == null) {
            LOG.atWarn().addKeyValue("eventId", e.eventId()).log("Nível de desempenho sem pessoa, vigência ou publicação; ignorado");
            metrics.eventConsumed("negotiator_tier_invalid");
            return;
        }
        PerformanceTier tier;
        try {
            tier = PerformanceTier.parse(e.tier());
        } catch (IllegalArgumentException ex) {
            // contrato quebrado: reprocessar não corrige; o log técnico registra para correção no incentive
            LOG.atWarn().addKeyValue("eventId", e.eventId()).addKeyValue("publicationId", e.publicationId())
                    .log("Nível de desempenho fora de A–D; ignorado");
            metrics.eventConsumed("negotiator_tier_invalid");
            return;
        }
        if (tiers.existsByUserIdAndPublicationId(e.userId(), e.publicationId())) {
            return;
        }
        tiers.save(new NegotiatorTier(e.userId(), tier, e.validFrom(), e.publicationId(), e.occurredAt()));
        metrics.eventConsumed("negotiator_tier");
    }
}
