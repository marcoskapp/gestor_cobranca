package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.AccountRoute;
import br.com.gestorcobranca.telephony.domain.repository.AccountRouteRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface AccountRouteJpaRepository extends JpaRepository<AccountRoute, UUID>, AccountRouteRepository {

    @Override
    @Query("select r from AccountRoute r where r.primaryAccountId = :accountId or r.fallbackAccountId = :accountId")
    List<AccountRoute> findUsingAccount(UUID accountId);
}
