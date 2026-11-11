package br.com.gestorcobranca.publicapi.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Tokens de integração cujos lotes de importação trouxeram títulos do devedor. */
public interface PublicApiDataSharingQueries {

    /** Uma linha por token ({@code recipientId}); {@code count} = lotes. */
    List<DataSharingUsage> tokensOf(UUID debtorId, Set<UUID> creditorScope);
}
