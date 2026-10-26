package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TelephonyAccountRepository {

    TelephonyAccount save(TelephonyAccount account);

    TelephonyAccount saveAndFlush(TelephonyAccount account);

    Optional<TelephonyAccount> findById(UUID id);

    List<TelephonyAccount> findAllByIdIn(java.util.Collection<UUID> ids);

    List<TelephonyAccount> findAllByOrderByNameAsc();
}
