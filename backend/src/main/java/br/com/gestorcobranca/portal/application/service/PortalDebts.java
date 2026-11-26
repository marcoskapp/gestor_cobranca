package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.ActiveAgreementRef;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dívidas do devedor que aparecem no portal: títulos do credor do portal, nas carteiras habilitadas,
 * em aberto ({@code OPEN}). Título suspenso (inclusive o que o credor informou como pago ou cancelado) não aparece:
 * o portal mostra só o que o devedor pode pagar agora (CDC art. 6º III e art. 42). Entra no portal quem tem título em
 * aberto <em>ou</em> acordo em vigor numa carteira habilitada (para acompanhar o acordo e pegar o Pix) — um único
 * critério ({@link #inPortal}) para código, verificação e link, sempre com as duas consultas, para que a resposta e o
 * tempo não revelem a situação da dívida (SEG-20261126-PA03).
 */
@Component
class PortalDebts {

    static final Set<String> IN_PORTAL = Set.of("OPEN");

    private final DebtorUseCases debtors;
    private final DebtTitleUseCases titles;
    private final AgreementUseCases agreements;
    private final ReversalHoldUseCases reversalHolds;

    PortalDebts(DebtorUseCases debtors, DebtTitleUseCases titles, AgreementUseCases agreements,
                ReversalHoldUseCases reversalHolds) {
        this.reversalHolds = reversalHolds;
        this.debtors = debtors;
        this.titles = titles;
        this.agreements = agreements;
    }

    /** Devedor do documento com dívida no portal (vazio se inexistente, anonimizado ou sem dívida no portal). */
    Optional<UUID> eligibleDebtor(PortalConfiguration config, Document document) {
        Optional<DebtorView> debtor = debtors.findByDocument(document.number());
        return debtor.filter(d -> "ACTIVE".equals(d.status())).map(DebtorView::id)
                .filter(id -> inPortal(config, id));
    }

    boolean hasDebtsInPortal(PortalConfiguration config, UUID debtorId) {
        try {
            return inPortal(config, debtorId);
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    /** Título em aberto ou acordo em vigor nas carteiras habilitadas (as duas consultas sempre rodam). */
    private boolean inPortal(PortalConfiguration config, UUID debtorId) {
        boolean open = !titlesInPortal(config, debtorId).isEmpty();
        boolean agreement = !activeAgreements(config, debtorId).isEmpty();
        return open || agreement;
    }

    /** Acordos em vigor do devedor com o credor do portal, nas carteiras habilitadas (consulta estreita do negotiation). */
    List<ActiveAgreementRef> activeAgreements(PortalConfiguration config, UUID debtorId) {
        return agreements.activeFor(config.getCreditorId(), debtorId).stream()
                .filter(a -> config.coversPortfolio(a.portfolioId()))
                .toList();
    }

    /**
     * Títulos usados no desafio de conhecimento (sufixo do contrato): os em aberto e os dos acordos em vigor, para
     * que quem entra só pelo acordo também consiga responder.
     */
    List<TitleView> knowledgeTitles(PortalConfiguration config, UUID debtorId) {
        Set<UUID> inAgreement = new HashSet<>();
        activeAgreements(config, debtorId).forEach(a -> inAgreement.addAll(a.titleIds()));
        return titles.listByDebtor(debtorId).stream()
                .filter(t -> t.creditorId().equals(config.getCreditorId()) && config.coversPortfolio(t.portfolioId())
                        && (IN_PORTAL.contains(t.status()) || inAgreement.contains(t.id())))
                .toList();
    }

    /**
     * Títulos em aberto do devedor nas carteiras habilitadas, por carteira e vencimento. Título com estorno de pagamento
     * recebido pelo credor em conferência não aparece para pagar nem negociar até a liberação (H07 parte 3, decisão
     * do responsável 4).
     */
    List<TitleView> titlesInPortal(PortalConfiguration config, UUID debtorId) {
        Set<UUID> held = reversalHolds.heldTitleIds(config.getCreditorId(), debtorId);
        return titles.listByDebtor(debtorId).stream()
                .filter(t -> t.creditorId().equals(config.getCreditorId()) && config.coversPortfolio(t.portfolioId())
                        && IN_PORTAL.contains(t.status()) && !held.contains(t.id()))
                .sorted(Comparator.comparing(TitleView::portfolioId).thenComparing(TitleView::dueDate))
                .toList();
    }

    DebtorView debtor(UUID debtorId) {
        return debtors.get(debtorId);
    }
}
