package br.com.gestorcobranca.assistant.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Turnos do assistente com o devedor em que um modelo de linguagem foi chamado, por provedor de IA. */
public interface AssistantDataSharingQueries {

    /** {@code code} = provedor gravado no turno; {@code latestRecordId} = turno mais recente. */
    List<DataSharingUsage> turnsByProvider(UUID debtorId, Set<UUID> creditorScope);

    /** A assessoria tem política do assistente publicada e ligada (modo diferente de {@code OFF}). */
    boolean hasActivePublishedPolicy();
}
