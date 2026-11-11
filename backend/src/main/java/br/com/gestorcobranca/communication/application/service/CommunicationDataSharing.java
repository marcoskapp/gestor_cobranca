package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.out.CommunicationDataSharingQueries;
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
import java.util.Locale;
import java.util.Map;

/**
 * Mensagens trocadas com o devedor, agrupadas pelo provedor da conta de canal (o provedor recebe o contato e o conteúdo
 * para entregar a mensagem). O link abre a conversa mais recente daquele provedor.
 */
@Component
class CommunicationDataSharing implements DataSharingSource {

    private static final Map<String, String> NAMES = Map.of(
            "WHATSAPP_CLOUD", "WhatsApp Business (Meta)",
            "SMTP", "Servidor de envio de e-mail (SMTP)",
            "SANDBOX", "Canal de demonstração (sandbox)");

    private final CommunicationDataSharingQueries queries;

    CommunicationDataSharing(CommunicationDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "communication";
    }

    @Override
    public String label() {
        return "Mensagens";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        return queries.messagesByProvider(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.PROVIDER, key(u.code()), null, name(u.code()),
                        DataSharingPurpose.MESSAGING, u.firstAt(), u.lastAt(), u.count(),
                        DataSharingFact.counted(u.count(), "mensagem", "mensagens"),
                        u.latestRecordId() == null ? null
                                : DataSharingLink.to("/conversas/" + u.latestRecordId(), "Abrir a conversa mais recente",
                                        Permission.INBOX_VIEW)))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return queries.activeProviders().stream()
                .map(p -> new DataSharingRecipient(key(p), name(p), DataSharingPurpose.MESSAGING))
                .toList();
    }

    static String key(String provider) {
        return "communication." + provider.toLowerCase(Locale.ROOT);
    }

    private static String name(String provider) {
        return NAMES.getOrDefault(provider, provider);
    }
}
