package br.com.gestorcobranca.shared.events.identity;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Nova assessoria provisionada (tenant, perfis padrão e administrador criados). Módulos que
 * precisam de configuração inicial por tenant (ex.: plano de comissão padrão do
 * {@code incentive}) reagem a este evento, sem que o provisionamento dependa deles.
 * {@code tenantId} é o da nova assessoria.
 */
public record TenantProvisioned(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID adminUserId) implements IntegrationEvent {
}
