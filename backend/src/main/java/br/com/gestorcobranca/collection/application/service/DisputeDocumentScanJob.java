package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.MalwareScanner;
import br.com.gestorcobranca.shared.storage.MalwareScanner.MalwareScanUnavailableException;
import br.com.gestorcobranca.shared.storage.MalwareScanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verificação de malware de um documento do credor ({@code collection.dispute_document_scan}), como ator de sistema no
 * tenant: limpo ou verificação desligada → vira evidência (cópia para EVIDENCE) e o arquivo da quarentena é apagado;
 * infectado → conteúdo apagado, registro com a assinatura, log de acesso {@code MALWARE_DETECTED} e alerta; verificador
 * indisponível → exceção (job reagendado); na última tentativa o documento fica {@code FAILED} (nunca é liberado sem
 * veredito).
 */
@Component
class DisputeDocumentScanJob implements JobHandler<DisputeDocumentScanJob.Payload> {

    static final String TYPE = "collection.dispute_document_scan";
    private static final Logger LOG = LoggerFactory.getLogger(DisputeDocumentScanJob.class);

    private final DisputeDocumentQuarantineRepository quarantine;
    private final DisputeRepository disputes;
    private final DisputeEvidenceStore evidenceStore;
    private final DisputeFileCleanup fileCleanup;
    private final FileStorage fileStorage;
    private final MalwareScanner scanner;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final DisputeQuarantineFailures failures;
    private final CollectionMetrics metrics;
    private final CollectionProperties properties;
    private final Clock clock;

    DisputeDocumentScanJob(DisputeDocumentQuarantineRepository quarantine, DisputeRepository disputes,
                           DisputeEvidenceStore evidenceStore, DisputeFileCleanup fileCleanup, FileStorage fileStorage,
                           MalwareScanner scanner, AuditTrail auditTrail, AccessLogger accessLogger,
                           BusinessEventLogger businessEvents, DisputeQuarantineFailures failures,
                           CollectionMetrics metrics, CollectionProperties properties, Clock clock) {
        this.quarantine = quarantine;
        this.disputes = disputes;
        this.evidenceStore = evidenceStore;
        this.fileCleanup = fileCleanup;
        this.fileStorage = fileStorage;
        this.scanner = scanner;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.failures = failures;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID documentId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.disputes().scanMaxAttempts();
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<DisputeDocumentQuarantine> found = quarantine.findById(payload.documentId());
        if (found.isEmpty() || !found.get().isPending()) {
            return;
        }
        DisputeDocumentQuarantine document = found.get();
        document.attempt();
        Instant started = Instant.now(clock);
        ScanResult result;
        try (InputStream in = fileStorage.open(document.getFileKey())) {
            result = scanner.scan(in);
        } catch (MalwareScanUnavailableException e) {
            metrics.disputeDocumentScan(scanner.engine(), "unavailable");
            if (context.attempt() >= maxAttempts()) {
                failures.exhausted(document.getId());
                metrics.jobFailure(TYPE);
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o documento da quarentena", e);
        }
        metrics.disputeDocumentScanDuration(scanner.engine(), Duration.between(started, Instant.now(clock)));
        Dispute dispute = disputes.findById(document.getDisputeId()).orElseThrow();
        Instant now = Instant.now(clock);
        if (result.verdict() == MalwareScanner.Verdict.INFECTED) {
            document.infected(scanner.engine(), result.signature(), now);
            quarantine.save(document);
            fileCleanup.deleteAfterCommit(document.getFileKey());
            auditTrail.record(AuditEntry.of("dispute.document_infected", "dispute", dispute.getId())
                    .creditor(dispute.getCreditorId())
                    .meta("documentId", document.getId())
                    .meta("sha256", document.getSha256())
                    .meta("engine", scanner.engine())
                    .meta("signature", document.getSignature()));
            accessLogger.log(AccessEvent.anonymous(AccessEventType.MALWARE_DETECTED, context.tenantId(), null,
                    Map.of("resource", "dispute_document", "resourceId", document.getId().toString(),
                            "creditorId", dispute.getCreditorId().toString(), "sha256", document.getSha256(),
                            "signature", String.valueOf(document.getSignature()))));
            LOG.atWarn().addKeyValue("documentId", document.getId()).addKeyValue("signature", document.getSignature())
                    .log("Documento do credor com malware: conteúdo apagado, não liberado");
            metrics.disputeDocumentScan(scanner.engine(), "infected");
        } else {
            DisputeEvidence evidence = evidenceStore.release(dispute, document);
            document.release(result.verdict() == MalwareScanner.Verdict.CLEAN ? DisputeDocumentQuarantine.Verdict.CLEAN
                    : DisputeDocumentQuarantine.Verdict.NOT_SCANNED, scanner.engine(), evidence.getId(), now);
            quarantine.save(document);
            fileCleanup.deleteAfterCommit(document.getFileKey());
            metrics.disputeDocumentScan(scanner.engine(), result.verdict().name().toLowerCase(java.util.Locale.ROOT));
        }
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_DOCUMENT_SCANNED, dispute)
                .attr("verdict", document.getVerdict())
                .attr("engine", scanner.engine())
                .attr("attempts", document.getAttempts())
                .attr("minutesInQuarantine", Duration.between(document.getCreatedAt(), now).toMinutes()));
    }
}
