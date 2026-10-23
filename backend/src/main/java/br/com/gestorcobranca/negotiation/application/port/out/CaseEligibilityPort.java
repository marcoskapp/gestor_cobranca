package br.com.gestorcobranca.negotiation.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Elegibilidade dos títulos para negociação (§5): status em cobrança, sem contestação impeditiva,
 * carteira operável e, a partir da parte 2, caso de cobrança elegível.
 * <p>
 * <strong>Adaptador provisório:</strong> {@code DebtBasedCaseEligibilityAdapter} decide pelo
 * módulo debt (status do título, bloqueio por contestação, saldo) e pela organization (carteira
 * operável). Na parte 2, quando o módulo collection estiver integrado, ele será substituído por um
 * adaptador que consulta o caso de cobrança (elegibilidade calculada, contestações F21), mantendo
 * este contrato e os códigos de motivo.
 */
public interface CaseEligibilityPort {

    /**
     * Avalia os títulos do devedor no credor. Títulos que não pertencem ao devedor/credor ou não
     * estão visíveis também são reportados como inelegíveis ({@code title.not_found}).
     */
    EligibilityResult check(UUID creditorId, UUID debtorId, Collection<UUID> titleIds);

    /**
     * Motivos estáveis: {@code title.not_found}, {@code title.not_open}, {@code title.no_balance},
     * {@code title.dispute_blocked}, {@code portfolio.not_operable}; (parte 2) {@code case.not_eligible}.
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
