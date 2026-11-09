package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintEvidenceRepository;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Verificação de malware de uma evidência de reclamação ({@code complaint.evidence_scan}), como ator de sistema no
 * tenant — mesmo comportamento do documento do credor nas contestações: limpo ou verificação desligada → cópia para
 * EVIDENCE e remoção da quarentena; infectado → conteúdo apagado, log de acesso {@code MALWARE_DETECTED} e alerta;
 * verificador indisponível → nova tentativa; na última, {@code FAILED} (nunca liberado sem veredito).
 */
@Component
class ComplaintEvidenceScanJob implements JobHandler<ComplaintEvidenceScanJob.Payload> {

    static final String TYPE = "complaint.evidence_scan";
    static final int MAX_ATTEMPTS = 8;
    private static final Logger LOG = LoggerFactory.getLogger(ComplaintEvidenceScanJob.class);

    record Payload(UUID evidenceId) {
    }

    private final ComplaintEvidenceRepository evidences;
    private final ComplaintRepository complaints;
    private final FileStorage fileStorage;
    private final MalwareScanner scanner;
    private final ComplaintFileCleanup fileCleanup;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final ComplaintMetrics metrics;
    private final Clock clock;

    ComplaintEvidenceScanJob(ComplaintEvidenceRepository evidences, ComplaintRepository complaints,
                             FileStorage fileStorage, MalwareScanner scanner, ComplaintFileCleanup fileCleanup,
                             AuditTrail auditTrail, AccessLogger accessLogger, BusinessEventLogger businessEvents,
                             ComplaintMetrics metrics, Clock clock) {
        this.evidences = evidences;
        this.complaints = complaints;
        this.fileStorage = fileStorage;
        this.scanner = scanner;
        this.fileCleanup = fileCleanup;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
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
        return MAX_ATTEMPTS;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        ComplaintEvidence evidence = evidences.findById(payload.evidenceId()).orElse(null);
        if (evidence == null || !evidence.isPending()) {
            return;
        }
        evidence.attempt();
        Instant started = Instant.now(clock);
        ScanResult result;
        try (InputStream in = fileStorage.open(evidence.getFileKey())) {
            result = scanner.scan(in);
        } catch (MalwareScanUnavailableException e) {
            metrics.evidenceScan(scanner.engine(), "unavailable");
            if (context.attempt() >= maxAttempts()) {
                evidence.failed(Instant.now(clock));
                evidences.save(evidence);
                LOG.atError().addKeyValue("evidenceId", evidence.getId())
                        .log("Evidência de reclamação sem veredito de malware após todas as tentativas; não liberada");
                return;
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler a evidência da quarentena", e);
        }
        metrics.evidenceScanDuration(scanner.engine(), Duration.between(started, Instant.now(clock)));
        Complaint complaint = complaints.findById(evidence.getComplaintId()).orElseThrow();
        Instant now = Instant.now(clock);
        String quarantineKey = evidence.getFileKey();
        if (result.verdict() == MalwareScanner.Verdict.INFECTED) {
            evidence.infected(scanner.engine(), result.signature(), now);
            evidences.save(evidence);
            fileCleanup.deleteAfterCommit(quarantineKey);
            auditTrail.record(AuditEntry.of("complaint.evidence_infected", "complaint", complaint.getId())
                    .creditor(complaint.getCreditorId())
                    .meta("evidenceId", evidence.getId())
                    .meta("sha256", evidence.getSha256())
                    .meta("engine", scanner.engine())
                    .meta("signature", evidence.getSignature()));
            accessLogger.log(AccessEvent.anonymous(AccessEventType.MALWARE_DETECTED, context.tenantId(), null,
                    Map.of("resource", "complaint_evidence", "resourceId", evidence.getId().toString(),
                            "creditorId", complaint.getCreditorId().toString(), "sha256", evidence.getSha256(),
                            "signature", String.valueOf(evidence.getSignature()))));
            LOG.atWarn().addKeyValue("evidenceId", evidence.getId()).addKeyValue("signature", evidence.getSignature())
                    .log("Evidência de reclamação com malware: conteúdo apagado, não liberado");
        } else {
            StoredFile stored;
            try (InputStream in = fileStorage.open(quarantineKey)) {
                stored = fileStorage.store(FileCategory.EVIDENCE, evidence.getFileName(), evidence.getContentType(), in,
                        evidence.getSize());
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao ler a evidência da quarentena", e);
            }
            fileCleanup.deleteOnRollback(stored.key());
            if (!stored.sha256().equals(evidence.getSha256())) {
                throw new IllegalStateException("A evidência mudou na quarentena");
            }
            evidence.release(result.verdict() == MalwareScanner.Verdict.CLEAN ? ComplaintEvidence.Verdict.CLEAN
                    : ComplaintEvidence.Verdict.NOT_SCANNED, scanner.engine(), stored.key(), now);
            evidences.save(evidence);
            fileCleanup.deleteAfterCommit(quarantineKey);
            auditTrail.record(AuditEntry.of("complaint.evidence_attached", "complaint", complaint.getId())
                    .creditor(complaint.getCreditorId())
                    .created("evidenceId", evidence.getId())
                    .created("fileName", evidence.getFileName())
                    .created("sha256", evidence.getSha256())
                    .meta("verdict", evidence.getVerdict()));
        }
        metrics.evidenceScan(scanner.engine(), result.verdict().name().toLowerCase(Locale.ROOT));
        businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_EVIDENCE_SCANNED, complaint)
                .attr("verdict", evidence.getVerdict())
                .attr("engine", scanner.engine())
                .attr("attempts", evidence.getAttempts())
                .attr("minutesInQuarantine", Duration.between(evidence.getCreatedAt(), now).toMinutes()));
    }
}
