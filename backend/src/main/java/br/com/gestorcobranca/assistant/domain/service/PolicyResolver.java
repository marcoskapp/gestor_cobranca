package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.PolicyStatus;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Política vigente para uma conversa: a publicada mais específica entre carteira, credor e assessoria
 * (docs/negocio/assistente-ia.md §6). A desativação de uma carteira (versão {@code OFF} da carteira)
 * prevalece sobre o credor e a assessoria imediatamente.
 */
public final class PolicyResolver {

    private PolicyResolver() {
    }

    public static Optional<AssistantPolicy> resolve(Collection<AssistantPolicy> candidates, UUID creditorId,
                                                    UUID portfolioId) {
        return candidates.stream()
                .filter(p -> p.getStatus() == PolicyStatus.PUBLISHED)
                .filter(p -> applies(p, creditorId, portfolioId))
                .max(Comparator.comparingInt(AssistantPolicy::specificity)
                        .thenComparingInt(AssistantPolicy::getPolicyVersion));
    }

    private static boolean applies(AssistantPolicy p, UUID creditorId, UUID portfolioId) {
        if (p.getPortfolioId() != null) {
            return portfolioId != null && p.getPortfolioId().equals(portfolioId);
        }
        if (p.getCreditorId() != null) {
            return Objects.equals(p.getCreditorId(), creditorId);
        }
        return true;
    }
}
