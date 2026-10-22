package br.com.gestorcobranca.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de integração entre módulos (linguagem publicada). Permite que um módulo reaja a
 * fatos de outro sem criar dependência cíclica.
 * <p>
 * Publicação: {@code ApplicationEventPublisher.publishEvent(evento)} dentro da transação do
 * caso de uso. O Spring Modulith registra a publicação na mesma transação e só a entrega após
 * o commit; publicações não concluídas são reenviadas ({@code EventRepublisher}).
 * <p>
 * Consumo: estenda {@link TenantEventListener} — o consumidor deve ser idempotente; a classe
 * base garante deduplicação por {@code eventId} e execução no tenant do evento.
 * <p>
 * Regras dos records: imutáveis, somente ids, enums como texto, valores {@code BigDecimal};
 * nunca dados pessoais.
 */
public interface IntegrationEvent {

    UUID eventId();

    UUID tenantId();

    Instant occurredAt();
}
