package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPortalAccess;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O portal está aberto para o credor? Liberado pela assessoria <b>e</b> credor ativo no cadastro: desativar o credor
 * (fim do contrato) fecha o portal na requisição seguinte, sem esperar alguém desligar a liberação. Reativar o credor
 * não reabre sozinho além do que a liberação já diz. Roda na transação (e no tenant) de quem chama.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CreditorPortalGate {

    private final CreditorAccountRepository accounts;
    private final CreditorUseCases creditors;

    CreditorPortalGate(CreditorAccountRepository accounts, CreditorUseCases creditors) {
        this.accounts = accounts;
        this.creditors = creditors;
    }

    public boolean isOpen(UUID creditorId) {
        if (!accounts.findAccess(creditorId).map(CreditorPortalAccess::isEnabled).orElse(false)) {
            return false;
        }
        try {
            return "ACTIVE".equals(creditors.get(creditorId).status());
        } catch (DomainException e) {
            return false;
        }
    }
}
