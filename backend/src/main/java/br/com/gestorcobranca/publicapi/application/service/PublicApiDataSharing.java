package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.PublicApiDataSharingQueries;
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
 * Integrações do credor (tokens da API pública) que enviaram títulos do devedor. O sistema do credor é o mesmo
 * controlador dos títulos: aparece para o titular saber por onde os dados entraram. O link abre o token.
 */
@Component
class PublicApiDataSharing implements DataSharingSource {

    private final PublicApiDataSharingQueries queries;

    PublicApiDataSharing(PublicApiDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "publicapi";
    }

    @Override
    public String label() {
        return "Integrações";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        return queries.tokensOf(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.INTEGRATION, "publicapi.token:" + u.recipientId(),
                        u.recipientId(), u.name(), DataSharingPurpose.CREDITOR_INTEGRATION, u.firstAt(), u.lastAt(),
                        u.count(), DataSharingFact.counted(u.count(), "lote de importação", "lotes de importação"),
                        DataSharingLink.to("/integracoes/tokens/" + u.recipientId(), "Abrir a integração",
                                Permission.PUBLIC_API_TOKEN_VIEW)))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return List.of();
    }
}
