package br.com.gestorcobranca.payment.application.port.out;

import br.com.gestorcobranca.shared.privacy.DataSharingUsage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Cobranças Pix do devedor por conta Pix (PSP) e as contas Pix ativas. */
public interface PaymentDataSharingQueries {

    /** Uma linha por conta Pix ({@code recipientId} = conta). @param creditorScope nulo = todos os credores */
    List<DataSharingUsage> pixChargesByAccount(UUID debtorId, Set<UUID> creditorScope);

    /** Contas Pix ativas ({@code recipientId} e nome). */
    List<DataSharingUsage> activeAccounts();
}
