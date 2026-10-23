package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Recibos em {@code communication.webhook_receipt} (RLS: tenant da transação). */
@Component
class JdbcWebhookReceiptLog implements WebhookReceiptLog {

    private final JdbcTemplate jdbc;

    JdbcWebhookReceiptLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerIfFirst(UUID channelAccountId, String payloadSha256, Instant receivedAt) {
        int inserted = jdbc.update("""
                insert into communication.webhook_receipt (id, tenant_id, channel_account_id, payload_sha256, received_at)
                values (?, shared.current_tenant_id(), ?, ?, ?)
                on conflict (tenant_id, channel_account_id, payload_sha256) do nothing
                """, Ids.newId(), channelAccountId, payloadSha256, Timestamp.from(receivedAt));
        return inserted == 1;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("delete from communication.webhook_receipt where received_at < ?", Timestamp.from(cutoff));
    }
}
