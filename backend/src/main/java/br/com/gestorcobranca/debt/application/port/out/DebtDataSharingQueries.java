package br.com.gestorcobranca.debt.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Credores que têm ou tiveram títulos do devedor (titularidade atual e histórico de cessões). */
public interface DebtDataSharingQueries {

    /** @param creditorScope nulo = todos os credores */
    List<DataSharingUsage> creditorsOf(UUID debtorId, Set<UUID> creditorScope);
}
