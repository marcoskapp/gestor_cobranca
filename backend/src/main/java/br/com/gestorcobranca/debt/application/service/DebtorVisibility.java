package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Escopo por credor aplicado ao devedor, que não pertence a um credor: o devedor é visível se
 * tiver ao menos um título num credor do escopo do ator (F02/F05). Devedores sem títulos só
 * são visíveis a quem tem acesso a todos os credores.
 */
@Component
class DebtorVisibility {

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final AccessScope accessScope;

    DebtorVisibility(DebtorRepository debtors, DebtTitleRepository titles, AccessScope accessScope) {
        this.debtors = debtors;
        this.titles = titles;
        this.accessScope = accessScope;
    }

    /** @throws ResourceNotFoundException se inexistente ou fora do escopo (registra no log de acesso) */
    Debtor requireVisible(UUID debtorId) {
        Debtor debtor = debtors.findById(debtorId).orElseThrow(() -> ResourceNotFoundException.of("debtor", debtorId));
        if (!accessScope.hasAllCreditors()) {
            accessScope.requireVisibleInAny("debtor", debtorId, titles.findCreditorIdsByDebtorId(debtorId));
        }
        return debtor;
    }

    /**
     * Para comandos que afetam o devedor como um todo (ex.: anonimização): exige escopo sobre
     * todos os credores em que o devedor tem títulos.
     */
    Debtor requireFullScope(UUID debtorId) {
        Debtor debtor = requireVisible(debtorId);
        titles.findCreditorIdsByDebtorId(debtorId).forEach(accessScope::requireCreditor);
        return debtor;
    }

    Optional<Debtor> findVisible(Debtor debtor) {
        if (accessScope.hasAllCreditors()) {
            return Optional.of(debtor);
        }
        List<UUID> creditorIds = titles.findCreditorIdsByDebtorId(debtor.getId());
        return creditorIds.stream().anyMatch(accessScope::canAccessCreditor) ? Optional.of(debtor) : Optional.empty();
    }

    /** Credores visíveis para filtros de listagem; {@code null} = sem restrição. */
    Collection<UUID> allowedCreditors() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
    }
}
