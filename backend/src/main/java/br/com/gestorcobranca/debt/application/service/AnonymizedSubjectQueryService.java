package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class AnonymizedSubjectQueryService implements AnonymizedSubjectQueries {

    private static final String RESOURCE = "debtor";

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final AccessScope accessScope;

    AnonymizedSubjectQueryService(DebtorRepository debtors, DebtTitleRepository titles,
                                  OriginContractRepository contracts, AccessScope accessScope) {
        this.debtors = debtors;
        this.titles = titles;
        this.contracts = contracts;
        this.accessScope = accessScope;
    }

    @Override
    public List<TitleReference> referencesOf(UUID debtorId) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        if (debtors.findById(debtorId).filter(Debtor::isAnonymized).isEmpty()) {
            return List.of();
        }
        return titles.findByDebtor(debtorId, null).stream()
                .map(t -> new TitleReference(t.getCreditorId(), t.getPortfolioId(), t.getExternalId(),
                        Document.maskIfDocument(t.getExternalId()), t.getInstallmentNumber()))
                .toList();
    }

    @Override
    public AnonymizedKeys anonymizedKeys(UUID creditorId, Collection<String> titleExternalIds,
                                         Collection<String> contractExternalIds) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        accessScope.requireCreditor(creditorId);
        // como a carga: identificador sem espaços nas pontas, nulos e vazios fora
        Set<String> titleKeys = normalized(titleExternalIds);
        Set<String> contractKeys = normalized(contractExternalIds);
        Set<String> anonymizedTitles = titleKeys.isEmpty() ? Set.of()
                : Set.copyOf(titles.findExternalIdsOfAnonymizedDebtors(creditorId, titleKeys));
        Set<String> anonymizedContracts = contractKeys.isEmpty() ? Set.of()
                : Set.copyOf(contracts.findExternalIdsOfAnonymizedDebtors(creditorId, contractKeys));
        return new AnonymizedKeys(anonymizedTitles, anonymizedContracts);
    }

    private static Set<String> normalized(Collection<String> ids) {
        return ids == null ? Set.of() : ids.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
