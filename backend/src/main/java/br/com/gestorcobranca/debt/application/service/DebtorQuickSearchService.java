package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorQuickSearch;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.debt.domain.service.QuickSearchTerm;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Busca rápida de devedores ({@link DebtorQuickSearch}). Cada critério consulta por índice (blind index do documento
 * e do telefone, trigrama do nome, código do título) com limite; os candidatos passam pela visibilidade em lote
 * ({@link DebtorVisibility#filterVisible}) e o resultado traz só os credores do escopo.
 */
@Service
@Transactional(readOnly = true)
class DebtorQuickSearchService implements DebtorQuickSearch {

    static final String NAME = "NAME";
    static final String DOCUMENT = "DOCUMENT";
    static final String PHONE = "PHONE";
    static final String TITLE_CODE = "TITLE_CODE";
    /** Candidatos lidos por critério antes do filtro de escopo (o excedente indica resultado truncado). */
    private static final int CANDIDATES = 50;

    private final DebtorRepository debtors;
    private final DebtorContactRepository contacts;
    private final DebtTitleRepository titles;
    private final ObligationPartyRepository parties;
    private final DebtorVisibility visibility;
    private final PersonalDataIndex index;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    DebtorQuickSearchService(DebtorRepository debtors, DebtorContactRepository contacts, DebtTitleRepository titles,
                             ObligationPartyRepository parties, DebtorVisibility visibility, PersonalDataIndex index,
                             AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger) {
        this.debtors = debtors;
        this.contacts = contacts;
        this.titles = titles;
        this.parties = parties;
        this.visibility = visibility;
        this.index = index;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    @Override
    public QuickSearchResult quickSearch(String raw) {
        accessScope.requirePermission(Permission.DEBTOR_VIEW);
        QuickSearchTerm term = QuickSearchTerm.of(raw);
        if (term.isEmpty()) {
            return new QuickSearchResult(List.of(), List.of(), false);
        }
        List<String> criteria = new ArrayList<>();
        // devedor → critério que o encontrou primeiro (ordem: documento, telefone, código, nome)
        Map<UUID, String> found = new LinkedHashMap<>();
        boolean more = false;

        if (term.document().isPresent()) {
            criteria.add(DOCUMENT);
            // a visibilidade vem antes de decidir pelo telefone: documento de devedor fora do escopo conta como "não achou"
            debtors.findByDocumentHash(index.document(term.document().get()))
                    .flatMap(visibility::findVisible)
                    .ifPresent(d -> found.putIfAbsent(d.getId(), DOCUMENT));
        }
        // um CPF válido também pode ser celular: o telefone só é buscado se o documento não achou ninguém visível
        if (term.phone().isPresent() && found.isEmpty()) {
            criteria.add(PHONE);
            // escopo aplicado na consulta: o limite (e o "há mais") conta só devedores do escopo
            List<UUID> ids = contacts.findDebtorIdsByTypeAndValueHash(ContactType.PHONE,
                    index.contact(term.phone().get()), visibility.allowedCreditors(), CANDIDATES + 1);
            more |= ids.size() > CANDIDATES;
            ids.stream().limit(CANDIDATES).forEach(id -> found.putIfAbsent(id, PHONE));
        }
        if (term.titleCode().isPresent()) {
            criteria.add(TITLE_CODE);
            List<UUID> ids = titles.findDebtorIdsByExternalId(term.titleCode().get(), visibility.allowedCreditors(),
                    CANDIDATES + 1);
            more |= ids.size() > CANDIDATES;
            ids.stream().limit(CANDIDATES).forEach(id -> found.putIfAbsent(id, TITLE_CODE));
        }

        List<Debtor> visible = found.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(visibility.filterVisible(debtors.findAllByIdIn(found.keySet())));
        // ordem dos critérios preservada (findAllByIdIn não garante ordem)
        List<UUID> order = List.copyOf(found.keySet());
        visible.sort(Comparator.comparingInt(d -> order.indexOf(d.getId())));

        if (term.name().isPresent() && visible.size() < MAX_RESULTS) {
            criteria.add(NAME);
            var page = debtors.search(visibility.allowedCreditors(), null, null, term.name().get(), null,
                    PageRequest.of(0, MAX_RESULTS + 1, Sort.by("name", "id")));
            for (Debtor d : page.getContent()) {
                if (found.putIfAbsent(d.getId(), NAME) == null) {
                    visible.add(d);
                }
            }
            more |= page.getContent().size() > MAX_RESULTS;
        }

        boolean truncated = more || visible.size() > MAX_RESULTS;
        List<Debtor> shown = visible.stream().limit(MAX_RESULTS).toList();
        Map<UUID, List<UUID>> creditors = creditorsInScope(shown.stream().map(Debtor::getId).toList());
        List<DebtorHit> hits = shown.stream()
                .map(d -> new DebtorHit(d.getId(), d.getName(), d.maskedDocument(), d.getPersonType().name(),
                        d.getStatus().name(), found.get(d.getId()), creditors.getOrDefault(d.getId(), List.of())))
                .toList();
        if (term.isPersonalData()) {
            logPersonalDataSearch(criteria, hits);
        }
        return new QuickSearchResult(criteria, hits, truncated);
    }

    /** Credores (títulos e coobrigação vigente) que o ator pode ver, por devedor — duas consultas no total. */
    private Map<UUID, List<UUID>> creditorsInScope(List<UUID> debtorIds) {
        if (debtorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> byDebtor = new HashMap<>();
        Stream.concat(titles.findDebtorCreditors(debtorIds).stream(),
                        parties.findActiveDebtorCreditors(debtorIds).stream())
                .filter(dc -> accessScope.canAccessCreditor(dc.creditorId()))
                .forEach(dc -> byDebtor.computeIfAbsent(dc.debtorId(), k -> new LinkedHashSet<>()).add(dc.creditorId()));
        Map<UUID, List<UUID>> result = new HashMap<>();
        byDebtor.forEach((debtor, set) -> result.put(debtor, List.copyOf(set)));
        return result;
    }

    /** Critério, quantidade e ids devolvidos — nunca o termo (documento ou telefone completo). */
    private void logPersonalDataSearch(Collection<String> criteria, List<DebtorHit> hits) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", "debtor");
        details.put("purpose", "QUICK_SEARCH");
        details.put("criteria", String.join(",", criteria));
        details.put("returned", String.valueOf(hits.size()));
        if (!hits.isEmpty()) {
            details.put("resourceIds", hits.stream().map(h -> h.id().toString()).collect(Collectors.joining(",")));
        }
        accessLogger.log(AccessEvent.of(AccessEventType.PERSONAL_DATA_SEARCHED, currentActor.get(), details));
    }
}
