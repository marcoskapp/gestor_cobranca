package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.telephony.application.port.out.WebhookReceiptLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Recibos e eventos aplicados em {@code telephony.webhook_receipt}/{@code provider_event} (RLS: tenant da transação). */
@Component
class JdbcTelephonyWebhookReceipts implements WebhookReceiptLog {

    private final JdbcTemplate jdbc;

    JdbcTelephonyWebhookReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerBodyIfFirst(UUID accountId, String payloadSha256, Instant receivedAt) {
        return jdbc.update("""
                insert into telephony.webhook_receipt (id, tenant_id, provider_account_id, payload_sha256, received_at)
                values (?, shared.current_tenant_id(), ?, ?, ?)
                on conflict (tenant_id, provider_account_id, payload_sha256) do nothing
                """, Ids.newId(), accountId, payloadSha256, Timestamp.from(receivedAt)) == 1;
    }

    @Override
    public boolean registerEventIfFirst(UUID accountId, String providerEventId, String type, Instant receivedAt) {
        return jdbc.update("""
                insert into telephony.provider_event (id, tenant_id, provider_account_id, provider_event_id, event_type,
                                                      received_at)
                values (?, shared.current_tenant_id(), ?, ?, ?, ?)
                on conflict (tenant_id, provider_account_id, provider_event_id) do nothing
                """, Ids.newId(), accountId, providerEventId, type, Timestamp.from(receivedAt)) == 1;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        Timestamp at = Timestamp.from(cutoff);
        return jdbc.update("delete from telephony.webhook_receipt where received_at < ?", at)
                + jdbc.update("delete from telephony.provider_event where received_at < ?", at);
    }
}
