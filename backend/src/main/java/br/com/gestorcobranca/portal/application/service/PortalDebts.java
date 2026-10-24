package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dívidas do devedor que aparecem no portal: títulos do credor do portal, nas carteiras habilitadas,
 * em cobrança ({@code OPEN} ou {@code SUSPENDED}). Um devedor só entra no portal se tiver ao menos um.
 */
@Component
class PortalDebts {

    static final Set<String> IN_COLLECTION = Set.of("OPEN", "SUSPENDED");

    private final DebtorUseCases debtors;
    private final DebtTitleUseCases titles;

    PortalDebts(DebtorUseCases debtors, DebtTitleUseCases titles) {
        this.debtors = debtors;
        this.titles = titles;
    }

    /** Devedor do documento com dívida no portal (vazio se inexistente, anonimizado ou sem dívida no portal). */
    Optional<UUID> eligibleDebtor(PortalConfiguration config, Document document) {
        Optional<DebtorView> debtor = debtors.findByDocument(document.number());
        return debtor.filter(d -> "ACTIVE".equals(d.status())).map(DebtorView::id)
                .filter(id -> !titlesInPortal(config, id).isEmpty());
    }

    boolean hasDebtsInPortal(PortalConfiguration config, UUID debtorId) {
        try {
            return !titlesInPortal(config, debtorId).isEmpty();
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    /** Títulos em cobrança do devedor nas carteiras habilitadas, por carteira e vencimento. */
    List<TitleView> titlesInPortal(PortalConfiguration config, UUID debtorId) {
        return titles.listByDebtor(debtorId).stream()
                .filter(t -> t.creditorId().equals(config.getCreditorId()) && config.coversPortfolio(t.portfolioId())
                        && IN_COLLECTION.contains(t.status()))
                .sorted(Comparator.comparing(TitleView::portfolioId).thenComparing(TitleView::dueDate))
                .toList();
    }

    DebtorView debtor(UUID debtorId) {
        return debtors.get(debtorId);
    }
}
