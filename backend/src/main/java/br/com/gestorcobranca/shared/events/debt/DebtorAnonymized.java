package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Devedor anonimizado (LGPD art. 18 ou política de retenção), publicado pelo módulo debt na
 * transação da anonimização acionada pelo compliance ({@code DebtorUseCases#anonymize}). Os
 * módulos que guardam texto livre ou dados pessoais ligados ao devedor (ex.: justificativas de
 * concessão, evidências de aceite) consomem este evento e apagam ou neutralizam esses dados.
 * Somente o id: o evento não carrega dado pessoal.
 */
public record DebtorAnonymized(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID debtorId) implements IntegrationEvent {
}
