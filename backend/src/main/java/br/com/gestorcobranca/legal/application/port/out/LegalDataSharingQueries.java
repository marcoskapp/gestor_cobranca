package br.com.gestorcobranca.legal.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Escritórios parceiros responsáveis por casos jurídicos do devedor. */
public interface LegalDataSharingQueries {

    /** Uma linha por escritório ({@code recipientId}), com o caso mais recente em {@code latestRecordId}. */
    List<DataSharingUsage> lawFirmsOf(UUID debtorId, Set<UUID> creditorScope);
}
