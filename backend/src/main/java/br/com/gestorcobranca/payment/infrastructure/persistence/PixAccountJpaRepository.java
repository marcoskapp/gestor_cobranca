package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface PixAccountJpaRepository extends JpaRepository<PixAccount, UUID>, PixAccountRepository {

    @Override
    @Query("select a from PixAccount a where a.active = true and a.creditorId = :creditorId")
    Optional<PixAccount> findActiveForCreditor(@Param("creditorId") UUID creditorId);

    @Override
    @Query("select a from PixAccount a where a.active = true and a.creditorId is null")
    Optional<PixAccount> findActiveDefault();
}
