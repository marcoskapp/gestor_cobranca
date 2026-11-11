package br.com.gestorcobranca.communication.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Mensagens do devedor por provedor da conta de canal, e os provedores das contas ativas. */
public interface CommunicationDataSharingQueries {

    /**
     * Uma linha por provedor ({@code code} = {@code ProviderType}), com a conversa mais recente em {@code latestRecordId}.
     *
     * @param creditorScope nulo = todos os credores (conversas sem credor só entram sem restrição de escopo)
     */
    List<DataSharingUsage> messagesByProvider(UUID debtorId, Set<UUID> creditorScope);

    /** Provedores das contas de canal ativas. */
    List<String> activeProviders();
}
