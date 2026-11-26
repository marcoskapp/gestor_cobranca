package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorCreditorLinkQueries;
import br.com.gestorcobranca.debt.application.port.out.DebtorCreditorLinks;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ligação devedor–credor por consulta escalar (RS-124). Credor fora do escopo do ator ou id nulo = sem ligação (RC2-02),
 * sem consultar o banco: a resposta não diferencia credor fora do escopo de devedor sem dívida.
 */
@Service
@Transactional(readOnly = true)
class DebtorCreditorLinkService implements DebtorCreditorLinkQueries {

    private final DebtorCreditorLinks links;
    private final AccessScope accessScope;

    DebtorCreditorLinkService(DebtorCreditorLinks links, AccessScope accessScope) {
        this.links = links;
        this.accessScope = accessScope;
    }

    @Override
    public boolean linked(UUID creditorId, UUID debtorId) {
        if (creditorId == null || debtorId == null || !accessScope.canAccessCreditor(creditorId)) {
            return false;
        }
        return links.linked(creditorId, debtorId);
    }
}
