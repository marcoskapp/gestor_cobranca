package br.com.gestorcobranca.settlement.domain.repository;

import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.shared.domain.Money;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionEntryRepository {

    CommissionEntry save(CommissionEntry entry);

    Optional<CommissionEntry> findByReceiptId(UUID receiptId);

    List<CommissionEntry> findByReceiptIdIn(Collection<UUID> receiptIds);

    List<CommissionEntry> findByReversesEntryId(UUID entryId);

    /** Valor fixo líquido (lançamentos − estornos) já devido pelo acordo. */
    Money netFixedFee(UUID agreementId);
}
