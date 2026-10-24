package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface TelephonyAccountJpaRepository extends JpaRepository<TelephonyAccount, UUID>, TelephonyAccountRepository {

    @Override
    @Query("select a from TelephonyAccount a where a.creditorId is null and a.active = true")
    Optional<TelephonyAccount> findActiveDefault();

    @Override
    @Query("select a from TelephonyAccount a where a.creditorId = :creditorId and a.active = true")
    Optional<TelephonyAccount> findActiveForCreditor(UUID creditorId);
}
