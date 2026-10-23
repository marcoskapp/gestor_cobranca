package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.PixAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixAccountRepository {

    PixAccount save(PixAccount account);

    PixAccount saveAndFlush(PixAccount account);

    Optional<PixAccount> findById(UUID id);

    Optional<PixAccount> findActiveForCreditor(UUID creditorId);

    Optional<PixAccount> findActiveDefault();

    List<PixAccount> findAllByOrderByNameAsc();
}
