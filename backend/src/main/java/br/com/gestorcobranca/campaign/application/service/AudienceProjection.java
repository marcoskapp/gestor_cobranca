package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.AudienceStore;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Mantém o read model de audiência a partir da interface pública do collection
 * ({@code CaseUseCases#get}/{@code search}) — o módulo nunca lê as tabelas de outro módulo. Chamado ao
 * consumir os eventos do caso, antes de cada etapa (posição atual) e pela reconstrução em lotes.
 */
@Component
class AudienceProjection {

    private final CaseUseCases cases;
    private final AudienceStore store;
    private final CampaignMetrics metrics;
    private final Clock clock;

    AudienceProjection(CaseUseCases cases, AudienceStore store, CampaignMetrics metrics, Clock clock) {
        this.cases = cases;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Posição atual do caso, gravada na projeção. Casos nunca são removidos no collection: um id
     * desconhecido é inconsistência e a exceção sobe (a transação é desfeita).
     */
    AudienceSnapshot refresh(UUID caseId, String trigger) {
        return apply(cases.get(caseId), trigger);
    }

    AudienceSnapshot apply(CaseView view, String trigger) {
        int broken = store.find(view.id()).map(AudienceSnapshot::brokenAgreements).orElse(0);
        AudienceSnapshot snapshot = snapshot(view, Instant.now(clock)).withBrokenAgreements(broken);
        store.upsert(snapshot);
        metrics.audienceRefresh(trigger, "CLOSED".equals(view.status()) ? "closed" : "updated");
        return snapshot;
    }

    static AudienceSnapshot snapshot(CaseView v, Instant now) {
        return new AudienceSnapshot(v.id(), v.debtorId(), v.creditorId(), v.portfolioId(), v.status(), v.resumeStatus(),
                v.eligible(), v.eligibilityReasons(), v.contactableChannels(), zero(v.openBalance()),
                zero(v.eligibleBalance()), v.oldestDueDate(), v.ownerType() == null ? "NONE" : v.ownerType(),
                v.interactionCount(), v.effectiveContactCount(), v.lastInteractionAt(), v.lastEffectiveContactAt(),
                v.score(), 0, v.version(), now);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
