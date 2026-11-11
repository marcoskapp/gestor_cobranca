package br.com.gestorcobranca.telephony.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Chamadas do devedor enviadas a uma plataforma de telefonia, por provedor, e os provedores das plataformas ativas. */
public interface TelephonyDataSharingQueries {

    /** @param creditorScope nulo = todos os credores (chamadas sem credor só entram sem restrição de escopo) */
    List<DataSharingUsage> callsByProvider(UUID debtorId, Set<UUID> creditorScope);

    List<String> activeProviders();
}
