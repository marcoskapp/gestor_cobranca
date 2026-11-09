package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.QuickSearchQueries;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtorQuickSearch;
import br.com.gestorcobranca.debt.application.port.in.DebtorQuickSearch.QuickSearchResult;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Busca rápida ({@link QuickSearchQueries}): devedores pelo módulo debt e casos de cada um nos credores do escopo
 * (no máximo 10 devedores; os casos de todos numa consulta só, pelo índice do devedor).
 */
@Service
@Transactional(readOnly = true)
class QuickSearchService implements QuickSearchQueries {

    private final DebtorQuickSearch debtors;
    private final CollectionCaseRepository cases;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;

    QuickSearchService(DebtorQuickSearch debtors, CollectionCaseRepository cases, CreditorUseCases creditors,
                       PortfolioUseCases portfolios, AccessScope accessScope, CurrentActor currentActor) {
        this.debtors = debtors;
        this.cases = cases;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
    }

    @Override
    public QuickSearchView search(String term) {
        QuickSearchResult found = debtors.quickSearch(term);
        List<DebtorResult> debtorResults = found.debtors().stream()
                .map(d -> new DebtorResult(d.id(), d.name(), d.maskedDocument(), d.personType(), d.status(),
                        d.matchedBy(), d.creditorIds()))
                .toList();
        List<CaseResult> caseResults = new ArrayList<>();
        boolean truncated = found.truncated();
        if (currentActor.get().has(Permission.CASE_VIEW) && !debtorResults.isEmpty()) {
            // uma consulta para todos os devedores (índice do devedor), no escopo de credores
            List<UUID> order = debtorResults.stream().map(DebtorResult::id).toList();
            List<CollectionCase> all = new ArrayList<>(cases.findByDebtorIdInOrderByOpenedAtDesc(order).stream()
                    .filter(c -> accessScope.canAccessCreditor(c.getCreditorId()))
                    .toList());
            // ativos primeiro, depois a ordem dos devedores e, em cada um, do mais recente (ordenação estável)
            all.sort(Comparator.comparing((CollectionCase c) -> !c.getStatus().isActive())
                    .thenComparingInt(c -> order.indexOf(c.getDebtorId())));
            truncated |= all.size() > MAX_CASES;
            all.stream().limit(MAX_CASES).forEach(c -> caseResults.add(new CaseResult(c.getId(), c.getDebtorId(),
                    c.getCreditorId(), c.getPortfolioId(), c.getStatus().name())));
        }
        Set<UUID> creditorIds = new HashSet<>();
        debtorResults.forEach(d -> creditorIds.addAll(d.creditorIds()));
        caseResults.forEach(c -> creditorIds.add(c.creditorId()));
        Map<UUID, String> creditorNames = creditors.getAll(creditorIds).stream()
                .collect(Collectors.toMap(CreditorView::id, c -> c.tradeName() != null ? c.tradeName() : c.legalName()));
        Map<UUID, String> portfolioNames = portfolios.getAll(caseResults.stream().map(CaseResult::portfolioId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(PortfolioView::id, PortfolioView::name));
        return new QuickSearchView(found.criteria(), debtorResults, caseResults, creditorNames, portfolioNames,
                truncated);
    }
}
