package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Escopo por credor aplicado ao devedor, que não pertence a um credor: o devedor é visível se
 * tiver ao menos um título num credor do escopo do ator ou for coobrigado vigente de contrato
 * de um credor do escopo (F02/F05). Devedores sem títulos nem participações só são visíveis a
 * quem tem acesso a todos os credores.
 */
@Component
class DebtorVisibility {

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final ObligationPartyRepository parties;
    private final AccessScope accessScope;

    DebtorVisibility(DebtorRepository debtors, DebtTitleRepository titles, ObligationPartyRepository parties,
                     AccessScope accessScope) {
        this.debtors = debtors;
        this.titles = titles;
        this.parties = parties;
        this.accessScope = accessScope;
    }

    /** Credores em que o devedor tem títulos ou é coobrigado vigente. */
    private List<UUID> creditorsOf(UUID debtorId) {
        List<UUID> creditors = new ArrayList<>(titles.findCreditorIdsByDebtorId(debtorId));
        parties.findActiveCreditorIdsByDebtorId(debtorId).stream().filter(c -> !creditors.contains(c)).forEach(creditors::add);
        return creditors;
    }

    /** @throws ResourceNotFoundException se inexistente ou fora do escopo (registra no log de acesso) */
    Debtor requireVisible(UUID debtorId) {
        Debtor debtor = debtors.findById(debtorId).orElseThrow(() -> ResourceNotFoundException.of("debtor", debtorId));
        if (!accessScope.hasAllCreditors()) {
            accessScope.requireVisibleInAny("debtor", debtorId, creditorsOf(debtorId));
        }
        return debtor;
    }

    /**
     * Para comandos que afetam o devedor como um todo (ex.: anonimização): exige escopo sobre
     * todos os credores em que o devedor tem títulos ou é coobrigado.
     */
    Debtor requireFullScope(UUID debtorId) {
        Debtor debtor = requireVisible(debtorId);
        creditorsOf(debtorId).forEach(accessScope::requireCreditor);
        return debtor;
    }

    Optional<Debtor> findVisible(Debtor debtor) {
        if (accessScope.hasAllCreditors()) {
            return Optional.of(debtor);
        }
        List<UUID> creditorIds = creditorsOf(debtor.getId());
        return creditorIds.stream().anyMatch(accessScope::canAccessCreditor) ? Optional.of(debtor) : Optional.empty();
    }

    /**
     * Versão em lote de {@link #findVisible(Debtor)} (duas consultas no total, não duas por
     * devedor), mantendo a ordem recebida.
     */
    List<Debtor> filterVisible(List<Debtor> candidates) {
        if (accessScope.hasAllCreditors() || candidates.isEmpty()) {
            return candidates;
        }
        Collection<UUID> creditors = accessScope.restrictCreditors(null);
        if (creditors.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = candidates.stream().map(Debtor::getId).toList();
        Set<UUID> visible = new HashSet<>(titles.findDebtorIdsWithTitlesInCreditors(ids, creditors));
        visible.addAll(parties.findActiveDebtorIdsInCreditors(ids, creditors));
        return candidates.stream().filter(d -> visible.contains(d.getId())).toList();
    }

    /** Credores visíveis para filtros de listagem; {@code null} = sem restrição. */
    Collection<UUID> allowedCreditors() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
    }
}
