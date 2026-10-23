package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Recibos em {@code payment.webhook_receipt} (RLS: tenant da transação). */
@Component
class JdbcPixWebhookReceiptLog implements WebhookReceiptLog {

    private final JdbcTemplate jdbc;

    JdbcPixWebhookReceiptLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerIfFirst(UUID pixAccountId, String payloadSha256, Instant receivedAt) {
        int inserted = jdbc.update("""
                insert into payment.webhook_receipt (id, tenant_id, pix_account_id, payload_sha256, received_at)
                values (?, shared.current_tenant_id(), ?, ?, ?)
                on conflict (tenant_id, pix_account_id, payload_sha256) do nothing
                """, Ids.newId(), pixAccountId, payloadSha256, Timestamp.from(receivedAt));
        return inserted == 1;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("delete from payment.webhook_receipt where received_at < ?", Timestamp.from(cutoff));
    }
}
