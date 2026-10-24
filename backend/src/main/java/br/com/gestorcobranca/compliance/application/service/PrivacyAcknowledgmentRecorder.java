package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.model.PrivacyNoticeAcknowledgment;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.compliance.domain.repository.PrivacyNoticeAcknowledgmentRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.portal.PortalPrivacyAcknowledged;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Registra no compliance o reconhecimento do aviso de privacidade feito no portal de
 * autonegociação ({@code PortalPrivacyAcknowledged}), vinculado à base legal vigente do credor
 * na data (LGPD art. 9º). Consumidor {@code compliance.portal-privacy}, idempotente pelo evento.
 * Sem base legal vigente o reconhecimento é gravado assim mesmo (o titular foi informado) e a
 * ausência gera WARN e métrica para a governança regularizar o registro do credor.
 */
@Component
class PrivacyAcknowledgmentRecorder extends TenantEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyAcknowledgmentRecorder.class);
    static final String CONSUMER = "compliance.portal-privacy";

    private final PrivacyNoticeAcknowledgmentRepository acknowledgments;
    private final LegalBasisRecordRepository legalBases;
    private final AuditTrail auditTrail;
    private final MeterRegistry meterRegistry;

    PrivacyAcknowledgmentRecorder(PrivacyNoticeAcknowledgmentRepository acknowledgments,
                                  LegalBasisRecordRepository legalBases, AuditTrail auditTrail,
                                  MeterRegistry meterRegistry) {
        this.acknowledgments = acknowledgments;
        this.legalBases = legalBases;
        this.auditTrail = auditTrail;
        this.meterRegistry = meterRegistry;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PortalPrivacyAcknowledged event) {
        handleOnce(event, CONSUMER, () -> record(event));
    }

    void record(PortalPrivacyAcknowledged e) {
        if (acknowledgments.existsBySourceEventId(e.eventId())) {
            return;
        }
        LocalDate date = LocalDate.ofInstant(e.occurredAt(), TimeConfig.BUSINESS_ZONE);
        Optional<LegalBasisRecord> basis = legalBases.findEffective(e.creditorId(), null, date);
        PrivacyNoticeAcknowledgment ack = acknowledgments.save(new PrivacyNoticeAcknowledgment(e.debtorId(),
                e.creditorId(), "PORTAL", e.noticeVersion(), e.noticeSha256(), e.sessionId(), e.occurredAt(),
                basis.orElse(null), e.eventId()));
        auditTrail.record(AuditEntry.of("privacy_notice.acknowledged", "privacy_notice_acknowledgment", ack.getId())
                .creditor(e.creditorId()).created("channel", "PORTAL").created("noticeVersion", e.noticeVersion())
                .created("legalBasis", ack.getLegalBasis()).meta("debtorId", e.debtorId()));
        String outcome = basis.isPresent() ? "with_legal_basis" : "without_legal_basis";
        if (basis.isEmpty()) {
            LOG.atWarn().addKeyValue("creditorId", e.creditorId())
                    .log("Aviso de privacidade reconhecido sem base legal vigente registrada para o credor");
        }
        meterRegistry.counter("compliance.privacy_acknowledgments", "channel", "PORTAL", "outcome", outcome).increment();
    }
}
