package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TelephonyAccountRepository {

    TelephonyAccount save(TelephonyAccount account);

    TelephonyAccount saveAndFlush(TelephonyAccount account);

    Optional<TelephonyAccount> findById(UUID id);

    Optional<TelephonyAccount> findActiveDefault();

    Optional<TelephonyAccount> findActiveForCreditor(UUID creditorId);

    List<TelephonyAccount> findAllByOrderByNameAsc();
}
