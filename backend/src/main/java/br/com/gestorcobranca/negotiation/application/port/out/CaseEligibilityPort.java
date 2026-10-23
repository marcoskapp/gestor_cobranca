package br.com.gestorcobranca.negotiation.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Elegibilidade dos títulos para negociação (§5): status em cobrança, sem contestação impeditiva,
 * carteira operável e, a partir da parte 2, caso de cobrança elegível.
 * <p>
 * Adaptador: {@code CollectionCaseEligibilityAdapter}, sobre os casos de uso do módulo collection
 * (caso ativo e operável, contestações impeditivas F21) e do debt (status e saldo do título).
 */
public interface CaseEligibilityPort {

    /**
     * Avalia os títulos do devedor no credor. Títulos que não pertencem ao devedor/credor ou não
     * estão visíveis também são reportados como inelegíveis ({@code title.not_found}).
     */
    EligibilityResult check(UUID creditorId, UUID debtorId, Collection<UUID> titleIds);

    /** Caso de cobrança ativo do devedor na carteira (vínculo da proposta e do acordo). */
    Optional<CaseRef> activeCase(UUID debtorId, UUID portfolioId);

    /**
     * @param teamId    equipe responsável pelo caso (pool do assistente na remuneração variável)
     * @param ownerType ASSISTANT, QUEUE, USER ou NONE
     */
    record CaseRef(UUID caseId, UUID teamId, UUID assigneeUserId, String ownerType) {
    }

    /**
     * Motivos estáveis: {@code title.not_found}, {@code title.not_open}, {@code title.no_balance},
     * {@code title.dispute_blocked}, {@code portfolio.not_operable}, {@code case.not_eligible}.
     *
     * @param ineligible motivos por título inelegível
     */
    record EligibilityResult(List<UUID> eligible, Map<UUID, List<String>> ineligible) {

        public EligibilityResult {
            eligible = List.copyOf(eligible);
            ineligible = Map.copyOf(ineligible);
        }
    }
}
