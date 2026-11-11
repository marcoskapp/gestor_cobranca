package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.out.LegalDataSharingQueries;
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
 * Escritórios de advocacia parceiros responsáveis por casos jurídicos do devedor (recebem os dados do caso para a
 * cobrança judicial ou extrajudicial). O link abre o caso jurídico mais recente com o escritório.
 */
@Component
class LegalDataSharing implements DataSharingSource {

    private final LegalDataSharingQueries queries;

    LegalDataSharing(LegalDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "legal";
    }

    @Override
    public String label() {
        return "Jurídico";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        return queries.lawFirmsOf(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.LAW_FIRM, "legal.law_firm:" + u.recipientId(),
                        u.recipientId(), u.name(), DataSharingPurpose.LEGAL_COLLECTION, u.firstAt(), u.lastAt(),
                        u.count(), DataSharingFact.counted(u.count(), "caso jurídico", "casos jurídicos"),
                        DataSharingLink.to("/juridico/casos/" + u.latestRecordId(),
                                u.count() == 1 ? "Abrir o caso jurídico" : "Abrir o caso jurídico mais recente",
                                Permission.LEGAL_VIEW)))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return List.of();
    }
}
