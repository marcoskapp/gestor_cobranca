package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.domain.model.Collateral;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.CollateralRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class DebtorSheetService implements DebtorSheetQueries {

    private final DebtorVisibility visibility;
    private final DebtTitleRepository titles;
    private final DebtorContactRepository contacts;
    private final ObligationPartyRepository parties;
    private final OriginContractRepository contracts;
    private final CollateralRepository collaterals;
    private final AccessScope accessScope;
    private final Clock clock;

    DebtorSheetService(DebtorVisibility visibility, DebtTitleRepository titles, DebtorContactRepository contacts,
                       ObligationPartyRepository parties, OriginContractRepository contracts,
                       CollateralRepository collaterals, AccessScope accessScope, Clock clock) {
        this.visibility = visibility;
        this.titles = titles;
        this.contacts = contacts;
        this.parties = parties;
        this.contracts = contracts;
        this.collaterals = collaterals;
        this.accessScope = accessScope;
        this.clock = clock;
    }

    @Override
    public DebtorSummary summary(UUID debtorId) {
        Debtor debtor = visibility.requireVisible(debtorId);
        LocalDate today = DebtViews.today(clock);

        List<ContactSummary> usableContacts = contacts.findByDebtorIdOrderByTypeAscPriorityAscCreatedAtAsc(debtorId).stream()
                .filter(c -> c.getStatus().isUsable())
                .map(c -> new ContactSummary(c.getId(), c.getType().name(), c.maskedValue(), c.getLabel().name(),
                        c.isWhatsapp(), c.getStatus().name(), c.getPriority()))
                .toList();

        List<DebtTitle> visibleTitles = titles.findByDebtor(debtorId, visibility.allowedCreditors());
        Map<UUID, List<DebtTitle>> byCreditor = new LinkedHashMap<>();
        visibleTitles.forEach(t -> byCreditor.computeIfAbsent(t.getCreditorId(), k -> new ArrayList<>()).add(t));

        Money total = Money.ZERO;
        long maxOverdue = 0;
        List<CreditorPosition> positions = new ArrayList<>();
        for (Map.Entry<UUID, List<DebtTitle>> entry : byCreditor.entrySet()) {
            Money creditorOpen = Money.ZERO;
            long creditorMaxOverdue = 0;
            List<TitleSummary> summaries = new ArrayList<>();
            for (DebtTitle t : entry.getValue()) {
                long overdue = t.daysOverdue(today);
                if (t.getStatus().acceptsPostings()) {
                    creditorOpen = creditorOpen.plus(t.getOpenBalance());
                    creditorMaxOverdue = Math.max(creditorMaxOverdue, overdue);
                }
                summaries.add(new TitleSummary(t.getId(), t.getPortfolioId(), t.getExternalId(), t.getInstallmentNumber(),
                        t.getDueDate(), t.getPrincipalAmount().amount(), t.getOpenBalance().amount(), t.getStatus().name(),
                        t.isDisputeBlocked(), overdue));
            }
            positions.add(new CreditorPosition(entry.getKey(), creditorOpen.amount(), creditorMaxOverdue, summaries));
            total = total.plus(creditorOpen);
            maxOverdue = Math.max(maxOverdue, creditorMaxOverdue);
        }
        List<ObligationParty> coObligations = parties.findByDebtorIdAndEndedOnIsNullOrderByCreatedAtAsc(debtorId).stream()
                .filter(p -> accessScope.canAccessCreditor(p.getCreditorId())).toList();
        return new DebtorSummary(debtor.getId(), debtor.getName(), debtor.maskedDocument(), debtor.getPersonType().name(),
                debtor.getStatus().name(), usableContacts, positions, total.amount(), maxOverdue, visibleTitles.size(),
                coObligationSummaries(coObligations, today), collateralSummaries(debtorId, coObligations));
    }

    private List<CoObligationSummary> coObligationSummaries(List<ObligationParty> coObligations, LocalDate today) {
        if (coObligations.isEmpty()) {
            return List.of();
        }
        List<UUID> contractIds = coObligations.stream().map(ObligationParty::getOriginContractId).distinct().toList();
        Map<UUID, OriginContract> byId = new LinkedHashMap<>();
        contracts.findAllByIdIn(contractIds).forEach(c -> byId.put(c.getId(), c));
        Map<UUID, List<DebtTitle>> titlesByContract = new LinkedHashMap<>();
        titles.findByOriginContractIdInOrderByDueDateAsc(contractIds)
                .forEach(t -> titlesByContract.computeIfAbsent(t.getOriginContractId(), k -> new ArrayList<>()).add(t));
        List<CoObligationSummary> summaries = new ArrayList<>();
        for (ObligationParty p : coObligations) {
            OriginContract contract = byId.get(p.getOriginContractId());
            List<DebtTitle> contractTitles = titlesByContract.getOrDefault(contract.getId(), List.of());
            Money open = Money.ZERO;
            long maxOverdue = 0;
            for (DebtTitle t : contractTitles) {
                if (t.getStatus().acceptsPostings()) {
                    open = open.plus(t.getOpenBalance());
                    maxOverdue = Math.max(maxOverdue, t.daysOverdue(today));
                }
            }
            summaries.add(new CoObligationSummary(contract.getId(), contract.getCreditorId(), contract.getExternalId(),
                    p.getRole().name(), p.getSinceDate(), contract.getDebtorId(), open.amount(), maxOverdue,
                    contractTitles.size()));
        }
        return summaries;
    }

    /** Garantias dos contratos visíveis em que o devedor é principal ou coobrigado vigente. */
    private List<CollateralSummary> collateralSummaries(UUID debtorId, List<ObligationParty> coObligations) {
        Map<UUID, String> roleByContract = new LinkedHashMap<>();
        contracts.findByDebtorId(debtorId).stream().filter(c -> accessScope.canAccessCreditor(c.getCreditorId()))
                .forEach(c -> roleByContract.put(c.getId(), ObligationRole.PRINCIPAL.name()));
        coObligations.forEach(p -> roleByContract.putIfAbsent(p.getOriginContractId(), p.getRole().name()));
        if (roleByContract.isEmpty()) {
            return List.of();
        }
        List<CollateralSummary> summaries = new ArrayList<>();
        for (Collateral c : collaterals.findByOriginContractIdInOrderByCreatedAtAsc(roleByContract.keySet())) {
            summaries.add(new CollateralSummary(c.getId(), c.getOriginContractId(), c.getCreditorId(),
                    roleByContract.get(c.getOriginContractId()), c.getType().name(), c.getAsset().summary(),
                    c.getStatus().name(), c.getEstimatedValue() == null ? null : c.getEstimatedValue().amount(),
                    c.getLastAppraisalDate()));
        }
        return summaries;
    }
}
