package br.com.gestorcobranca.legal.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * Fatos que condicionam as transições de fase, reunidos pelo caso de uso a partir dos demais
 * agregados (viabilidade, autorização, processos).
 *
 * @param viabilityCompleted  há análise de viabilidade registrada
 * @param viable              conclusão da última análise (nula sem análise)
 * @param authorizedRoutes    vias da autorização do credor vigente (vazia sem autorização concedida)
 * @param lawsuitRegistered   há processo com número CNJ cadastrado
 * @param agreementId         acordo do negotiation que leva a SETTLED_IN_COURT
 * @param closureReason       motivo do encerramento (CLOSED)
 */
public record StageRequirements(boolean viabilityCompleted, Boolean viable, Set<LegalRoute> authorizedRoutes,
                                boolean lawsuitRegistered, UUID agreementId, ClosureReason closureReason) {

    public StageRequirements {
        authorizedRoutes = authorizedRoutes == null ? Set.of() : Set.copyOf(authorizedRoutes);
    }

    public boolean authorizedExtrajudicial() {
        return authorizedRoutes.stream().anyMatch(r -> !r.isJudicial());
    }

    public boolean authorizedJudicial() {
        return authorizedRoutes.stream().anyMatch(LegalRoute::isJudicial);
    }
}
