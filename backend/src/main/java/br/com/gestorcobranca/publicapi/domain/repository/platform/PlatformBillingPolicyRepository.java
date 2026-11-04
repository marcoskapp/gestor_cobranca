package br.com.gestorcobranca.publicapi.domain.repository.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicy;

import java.util.Optional;

/** Política da plataforma da assessoria corrente (uma por assessoria; RLS). */
public interface PlatformBillingPolicyRepository {

    PlatformBillingPolicy save(PlatformBillingPolicy policy);

    Optional<PlatformBillingPolicy> findCurrent();

    /**
     * Política com bloqueio da linha ({@code FOR UPDATE}) até o fim da transação: serializa novas versões, mudança de
     * vigência e fechamentos da assessoria entre instâncias (fechamento idempotente).
     */
    Optional<PlatformBillingPolicy> findCurrentForUpdate();
}
