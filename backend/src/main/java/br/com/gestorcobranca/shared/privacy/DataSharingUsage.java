package br.com.gestorcobranca.shared.privacy;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha agregada lida pelos adaptadores JDBC das fontes: um destinatário (provedor, conta, escritório, credor) com o
 * período e a contagem de uso para um devedor.
 *
 * @param code           código do provedor no módulo (ex.: {@code WHATSAPP_CLOUD}), quando houver
 * @param recipientId    id do registro do destinatário (conta, escritório, credor, token), quando houver
 * @param name           nome de exibição
 * @param firstAt        primeiro uso
 * @param lastAt         último uso
 * @param count          quantidade
 * @param latestRecordId registro mais recente (conversa, caso, turno) para abrir a evidência
 */
public record DataSharingUsage(String code, UUID recipientId, String name, Instant firstAt, Instant lastAt, long count,
                               UUID latestRecordId) {
}
