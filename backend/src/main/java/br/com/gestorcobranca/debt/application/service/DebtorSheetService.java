package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.shared.domain.Money;
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
    private final Clock clock;

    DebtorSheetService(DebtorVisibility visibility, DebtTitleRepository titles, DebtorContactRepository contacts,
                       Clock clock) {
        this.visibility = visibility;
        this.titles = titles;
        this.contacts = contacts;
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
        return new DebtorSummary(debtor.getId(), debtor.getName(), debtor.maskedDocument(), debtor.getPersonType().name(),
                debtor.getStatus().name(), usableContacts, positions, total.amount(), maxOverdue, visibleTitles.size());
    }
}
