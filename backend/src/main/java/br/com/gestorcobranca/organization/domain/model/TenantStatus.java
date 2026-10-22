package br.com.gestorcobranca.organization.domain.model;

public enum TenantStatus {
    ACTIVE,
    /** Acesso bloqueado (inadimplência contratual, encerramento); dados preservados. */
    SUSPENDED
}
