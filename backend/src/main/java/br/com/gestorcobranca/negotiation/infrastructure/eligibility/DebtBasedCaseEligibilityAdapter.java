package br.com.gestorcobranca.negotiation.infrastructure.eligibility;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <strong>Adaptador provisório</strong> de {@link CaseEligibilityPort} (parte 1), baseado no
 * módulo debt e na organization, enquanto o módulo collection não está integrado:
 * <ul>
 *   <li>{@code title.not_found} — título não pertence ao devedor/credor ou está fora do escopo;</li>
 *   <li>{@code title.not_open} — status diferente de {@code OPEN} (suspenso, quitado, cancelado, devolvido);</li>
 *   <li>{@code title.no_balance} — sem saldo em aberto;</li>
 *   <li>{@code title.dispute_blocked} — contestação impeditiva (F21) vinculada no razão;</li>
 *   <li>{@code portfolio.not_operable} — carteira inativa ou fora de vigência (RB02).</li>
 * </ul>
 * <p>
 * <strong>Substituição na parte 2:</strong> um adaptador do collection (caso de cobrança elegível,
 * contestações e bloqueios do caso) substituirá este bean, mantendo o contrato e os códigos; este
 * arquivo deve então ser removido para que não existam duas implementações da porta.
 */
@Component
class DebtBasedCaseEligibilityAdapter implements CaseEligibilityPort {

    private final DebtTitleUseCases titles;
    private final PortfolioUseCases portfolios;

    DebtBasedCaseEligibilityAdapter(DebtTitleUseCases titles, PortfolioUseCases portfolios) {
        this.titles = titles;
        this.portfolios = portfolios;
    }

    @Override
    public EligibilityResult check(UUID creditorId, UUID debtorId, Collection<UUID> titleIds) {
        Map<UUID, TitleView> debtorTitles = titles.listByDebtor(debtorId).stream()
                .filter(t -> creditorId.equals(t.creditorId()))
                .collect(Collectors.toMap(TitleView::id, Function.identity()));
        Set<UUID> portfolioIds = titleIds.stream().map(debtorTitles::get).filter(java.util.Objects::nonNull)
                .map(TitleView::portfolioId).collect(Collectors.toSet());
        Map<UUID, PortfolioView> portfolioById = portfolioIds.isEmpty() ? Map.of()
                : portfolios.getAll(portfolioIds).stream().collect(Collectors.toMap(PortfolioView::id, Function.identity()));
        List<UUID> eligible = new ArrayList<>();
        Map<UUID, List<String>> ineligible = new LinkedHashMap<>();
        for (UUID id : titleIds) {
            TitleView title = debtorTitles.get(id);
            List<String> reasons = new ArrayList<>();
            if (title == null) {
                reasons.add("title.not_found");
            } else {
                if (!"OPEN".equals(title.status())) {
                    reasons.add("title.not_open");
                }
                if (title.openBalance() == null || title.openBalance().signum() <= 0) {
                    reasons.add("title.no_balance");
                }
                if (title.disputeBlocked()) {
                    reasons.add("title.dispute_blocked");
                }
                PortfolioView portfolio = portfolioById.get(title.portfolioId());
                if (portfolio == null || !portfolio.operable()) {
                    reasons.add("portfolio.not_operable");
                }
            }
            if (reasons.isEmpty()) {
                eligible.add(id);
            } else {
                ineligible.put(id, List.copyOf(reasons));
            }
        }
        return new EligibilityResult(eligible, ineligible);
    }
}
