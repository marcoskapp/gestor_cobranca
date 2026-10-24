package br.com.gestorcobranca.shared.events.portal;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * O devedor reconheceu o aviso de privacidade do portal de autonegociação de um credor. Publicado
 * pelo {@code portal} (que não pode depender do {@code compliance} pelo grafo) e consumido pelo
 * {@code compliance} ({@code compliance.portal-privacy}), que registra o reconhecimento junto da
 * base legal vigente do credor/carteira (LGPD art. 9º — informação ao titular; a base do tratamento
 * da cobrança continua sendo a registrada para o credor, não o consentimento).
 *
 * @param noticeVersion versão do aviso exibido (configuração do portal do credor)
 * @param noticeSha256  SHA-256 do texto do aviso exibido (prova do conteúdo apresentado)
 * @param sessionId     sessão do portal em que houve o reconhecimento
 */
public record PortalPrivacyAcknowledged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID debtorId,
        UUID creditorId,
        UUID sessionId,
        int noticeVersion,
        String noticeSha256) implements IntegrationEvent {
}
