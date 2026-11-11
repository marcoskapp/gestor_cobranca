package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import br.com.gestorcobranca.shared.privacy.DataSharingSource;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyDataSharingQueries;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ligações com o devedor, agrupadas pela plataforma de telefonia (que recebe o número discado e, com gravação, o áudio).
 * O link abre a lista de chamadas filtrada pelo devedor.
 */
@Component
class TelephonyDataSharing implements DataSharingSource {

    private static final Map<String, String> NAMES = Map.of(
            "TWILIO", "Twilio",
            "PLIVO", "Plivo",
            "ALVARIA", "Alvaria",
            "TARIFANDO", "Tarifando",
            "SANDBOX", "Telefonia de demonstração (sandbox)");

    private final TelephonyDataSharingQueries queries;

    TelephonyDataSharing(TelephonyDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "telephony";
    }

    @Override
    public String label() {
        return "Telefonia";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        DataSharingLink calls = DataSharingLink.to("/telefonia/chamadas", "Ver as chamadas do devedor",
                Permission.TELEPHONY_SUPERVISE).withParam("devedor", query.debtorId().toString())
                // o filtro por devedor da lista de chamadas exige a busca de devedores
                .alsoRequiring(Permission.DEBTOR_VIEW);
        return queries.callsByProvider(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.PROVIDER, key(u.code()), null, name(u.code()),
                        DataSharingPurpose.PHONE_CALLS, u.firstAt(), u.lastAt(), u.count(),
                        DataSharingFact.counted(u.count(), "chamada", "chamadas"), calls))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return queries.activeProviders().stream()
                .map(p -> new DataSharingRecipient(key(p), name(p), DataSharingPurpose.PHONE_CALLS))
                .toList();
    }

    static String key(String provider) {
        return "telephony." + provider.toLowerCase(Locale.ROOT);
    }

    private static String name(String provider) {
        return NAMES.getOrDefault(provider, provider);
    }
}
