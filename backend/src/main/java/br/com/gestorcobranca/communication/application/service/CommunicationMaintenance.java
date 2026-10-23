package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionPolicyView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manutenção por assessoria (no tenant corrente): descarte do conteúdo de mensagens conforme a
 * política {@code MESSAGE_CONTENT} do compliance (metadados permanecem) e limpeza dos recibos de
 * webhook fora da janela de replay.
 */
@Component
class CommunicationMaintenance {

    static final String RETENTION_CATEGORY = "MESSAGE_CONTENT";

    private final MessageRepository messages;
    private final WebhookReceiptLog receipts;
    private final RetentionUseCases retention;
    private final CommunicationMetrics metrics;
    private final CommunicationProperties properties;
    private final Clock clock;

    CommunicationMaintenance(MessageRepository messages, WebhookReceiptLog receipts, RetentionUseCases retention,
                             CommunicationMetrics metrics, CommunicationProperties properties, Clock clock) {
        this.messages = messages;
        this.receipts = receipts;
        this.retention = retention;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return mensagens com conteúdo descartado neste lote (lote cheio = há mais) */
    @Transactional
    public int purgeMessageContent() {
        Optional<RetentionPolicyView> policy = retention.policyFor(RETENTION_CATEGORY).filter(RetentionPolicyView::enabled);
        if (policy.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant cutoff = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE)
                .minusMonths(policy.get().retentionMonths()).toInstant();
        List<Message> batch = messages.findContentOlderThan(cutoff, properties.retentionBatch());
        int purged = 0;
        for (Message m : batch) {
            if (m.purgeContent(now)) {
                messages.save(m);
                purged++;
            }
        }
        metrics.contentPurged(purged);
        return batch.size();
    }

    @Transactional
    public int purgeWebhookReceipts() {
        return receipts.purgeBefore(Instant.now(clock).minus(properties.webhook().maxEventAge()));
    }
}
