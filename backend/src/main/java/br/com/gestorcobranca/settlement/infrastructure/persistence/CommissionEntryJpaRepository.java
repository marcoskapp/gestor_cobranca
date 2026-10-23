package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

interface CommissionEntryJpaRepository extends JpaRepository<CommissionEntry, UUID>, CommissionEntryRepository {

    @Query(value = "select coalesce(sum(fixed_fee), 0) from settlement.commission_entry where agreement_id = :agreementId",
            nativeQuery = true)
    BigDecimal sumFixedFee(@Param("agreementId") UUID agreementId);

    @Override
    default Money netFixedFee(UUID agreementId) {
        return Money.ofNullable(sumFixedFee(agreementId));
    }
}
