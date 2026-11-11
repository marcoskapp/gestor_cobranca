package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.out.DebtDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import br.com.gestorcobranca.shared.privacy.DataSharingSource;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Credores com títulos do devedor (atuais e anteriores, pelas cessões): a assessoria trata os dados em nome deles. O
 * link abre a ficha do credor; a base legal de cada um é acrescentada pelo compliance.
 */
@Component
class DebtDataSharing implements DataSharingSource {

    private final DebtDataSharingQueries queries;

    DebtDataSharing(DebtDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "debt";
    }

    @Override
    public String label() {
        return "Credores";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        return queries.creditorsOf(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.CREDITOR, "debt.creditor:" + u.recipientId(),
                        u.recipientId(), u.name(), DataSharingPurpose.DEBT_COLLECTION, u.firstAt(), u.lastAt(), u.count(),
                        DataSharingFact.counted(u.count(), "título", "títulos"),
                        DataSharingLink.to("/organizacao/credores/" + u.recipientId(), "Abrir o credor",
                                Permission.ORGANIZATION_AREA_VIEW).alsoRequiring(Permission.CREDITOR_VIEW)))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return List.of();
    }
}
