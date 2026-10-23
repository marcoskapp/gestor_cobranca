package br.com.gestorcobranca.negotiation.infrastructure.eligibility;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Elegibilidade para negociação sobre os casos de uso do módulo collection (caso de cobrança e
 * contestações F21), do debt (status e saldo do título) e da organization (carteira operável):
 * <ul>
 *   <li>{@code title.not_found} — título não pertence ao devedor/credor ou está fora do escopo;</li>
 *   <li>{@code title.not_open} — status diferente de {@code OPEN};</li>
 *   <li>{@code title.no_balance} — sem saldo em aberto;</li>
 *   <li>{@code title.dispute_blocked} — contestação impeditiva aberta (em análise inclusive) que cobre o
 *       título, no caso de cobrança ou vinculada no razão;</li>
 *   <li>{@code portfolio.not_operable} — carteira inativa ou fora de vigência (RB02);</li>
 *   <li>{@code case.not_eligible} — sem caso de cobrança ativo na carteira, caso suspenso, no jurídico
 *       ou com devedor anonimizado.</li>
 * </ul>
 * Um caso em {@code DISPUTED} não bloqueia os títulos que a contestação não cobre (o devedor pode
 * pagar a parte incontroversa). Restrições de contato (opt-out, canais) não impedem negociar quando
 * o devedor procura a assessoria: elas são verificadas pelo porteiro do compliance a cada contato.
 */
@Component
class CollectionCaseEligibilityAdapter implements CaseEligibilityPort {

    private static final Set<String> NOT_OPERABLE_CASE = Set.of("CLOSED", "SUSPENDED", "LEGAL");
    private static final Set<String> OPEN_DISPUTE = Set.of("OPEN", "UNDER_REVIEW");

    private final DebtTitleUseCases titles;
    private final PortfolioUseCases portfolios;
    private final CaseUseCases cases;
    private final DisputeUseCases disputes;

    CollectionCaseEligibilityAdapter(DebtTitleUseCases titles, PortfolioUseCases portfolios, CaseUseCases cases,
                                     DisputeUseCases disputes) {
        this.titles = titles;
        this.portfolios = portfolios;
        this.cases = cases;
        this.disputes = disputes;
    }

    @Override
    public EligibilityResult check(UUID creditorId, UUID debtorId, Collection<UUID> titleIds) {
        Map<UUID, TitleView> debtorTitles = titles.listByDebtor(debtorId).stream()
                .filter(t -> creditorId.equals(t.creditorId()))
                .collect(Collectors.toMap(TitleView::id, Function.identity()));
        Set<UUID> portfolioIds = titleIds.stream().map(debtorTitles::get).filter(Objects::nonNull)
                .map(TitleView::portfolioId).collect(Collectors.toSet());
        Map<UUID, PortfolioView> portfolioById = portfolioIds.isEmpty() ? Map.of()
                : portfolios.getAll(portfolioIds).stream().collect(Collectors.toMap(PortfolioView::id, Function.identity()));
        Map<UUID, Optional<CaseView>> caseByPortfolio = new HashMap<>();
        Set<UUID> disputed = new HashSet<>();
        for (UUID portfolioId : portfolioIds) {
            Optional<CaseView> c = cases.findActive(debtorId, portfolioId);
            caseByPortfolio.put(portfolioId, c);
            c.ifPresent(found -> disputes.listByCase(found.id()).stream()
                    .filter(d -> OPEN_DISPUTE.contains(d.status()) && d.blocksCollection())
                    .map(DisputeView::titleIds).forEach(disputed::addAll));
        }
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
                if (title.disputeBlocked() || disputed.contains(id)) {
                    reasons.add("title.dispute_blocked");
                }
                PortfolioView portfolio = portfolioById.get(title.portfolioId());
                if (portfolio == null || !portfolio.operable()) {
                    reasons.add("portfolio.not_operable");
                }
                Optional<CaseView> c = caseByPortfolio.getOrDefault(title.portfolioId(), Optional.empty());
                if (c.isEmpty() || NOT_OPERABLE_CASE.contains(c.get().status())
                        || c.get().eligibilityReasons().contains("DEBTOR_ANONYMIZED")) {
                    reasons.add("case.not_eligible");
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

    @Override
    public Optional<CaseRef> activeCase(UUID debtorId, UUID portfolioId) {
        return cases.findActive(debtorId, portfolioId)
                .map(c -> new CaseRef(c.id(), c.assigneeTeamId(), c.assigneeUserId(), c.ownerType()));
    }
}
