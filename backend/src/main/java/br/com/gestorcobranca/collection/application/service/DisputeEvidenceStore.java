package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.storage.FileSignatures;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Gravação de evidências da contestação (anexadas pela assessoria ou trazidas na resposta do credor): validação de
 * tipo, tamanho e limite, armazenamento na categoria EVIDENCE com SHA-256 (removido se a transação for desfeita) e
 * auditoria.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeEvidenceStore {

    /** Tipos de arquivo aceitos como evidência (documentos, imagens, áudio, texto, e-mail). */
    static final Set<String> EVIDENCE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg",
            "audio/mpeg", "audio/wav", "audio/ogg", "text/plain", "message/rfc822");

    private final DisputeEvidenceRepository evidences;
    private final DisputeDocumentQuarantineRepository quarantine;
    private final FileStorage fileStorage;
    private final DisputeFileCleanup fileCleanup;
    private final AuditTrail auditTrail;
    private final JobQueue jobQueue;
    private final CollectionProperties properties;

    DisputeEvidenceStore(DisputeEvidenceRepository evidences, DisputeDocumentQuarantineRepository quarantine,
                         FileStorage fileStorage, DisputeFileCleanup fileCleanup, AuditTrail auditTrail,
                         JobQueue jobQueue, CollectionProperties properties) {
        this.evidences = evidences;
        this.quarantine = quarantine;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.auditTrail = auditTrail;
        this.jobQueue = jobQueue;
        this.properties = properties;
    }

    /**
     * Documento do credor pela API: tipo conferido pelo conteúdo (PDF, PNG ou JPEG), tamanho e limite; gravado na
     * quarentena e verificado pelo job {@code collection.dispute_document_scan} antes de virar evidência.
     */
    DisputeDocumentQuarantine quarantine(Dispute dispute, EvidenceUpload upload, UUID creditorResponseId) {
        dispute.requireOpenForEvidence();
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "dispute.evidence_empty",
                "Arquivo vazio");
        Guard.require(upload.size() <= properties.disputes().apiMaxDocumentBytes(), "dispute.evidence_too_large",
                "Documento excede o tamanho máximo de %d MB".formatted(properties.disputes().apiMaxDocumentBytes()
                        / (1024 * 1024)));
        long inUse = evidences.countByDisputeId(dispute.getId())
                + quarantine.countByDisputeIdAndStatus(dispute.getId(), DisputeDocumentQuarantine.Status.PENDING);
        if (inUse >= DisputeEvidence.MAX_PER_DISPUTE) {
            throw new ConflictException("dispute.evidence_limit", "Limite de %d evidências por contestação"
                    .formatted(DisputeEvidence.MAX_PER_DISPUTE));
        }
        FileSignatures.Sniffed sniffed = FileSignatures.sniff(upload.content());
        String contentType = sniffed.contentType().orElseThrow(() -> new BusinessRuleException(
                "dispute.evidence_type_invalid", "Documento deve ser PDF, PNG ou JPEG (conferido pelo conteúdo)"));
        StoredFile stored = fileStorage.store(FileCategory.QUARANTINE, upload.fileName(), contentType, sniffed.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        DisputeDocumentQuarantine document = quarantine.save(new DisputeDocumentQuarantine(dispute, creditorResponseId,
                stored.key(), stored.originalName(), contentType, stored.size(), stored.sha256()));
        auditTrail.record(AuditEntry.of("dispute.document_quarantined", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("documentId", document.getId())
                .created("fileName", document.getFileName())
                .created("sha256", document.getSha256())
                .meta("size", document.getSize())
                .meta("creditorResponseId", creditorResponseId));
        jobQueue.enqueue(DisputeDocumentScanJob.TYPE, new DisputeDocumentScanJob.Payload(document.getId()), null,
                "dispute_document_scan:" + document.getId());
        return document;
    }

    /** Documento limpo (ou verificação desligada): copia para EVIDENCE e cria a evidência (append-only). */
    DisputeEvidence release(Dispute dispute, DisputeDocumentQuarantine document) {
        StoredFile stored;
        try (java.io.InputStream in = fileStorage.open(document.getFileKey())) {
            stored = fileStorage.store(FileCategory.EVIDENCE, document.getFileName(), document.getContentType(), in,
                    document.getSize());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Falha ao ler o documento da quarentena", e);
        }
        fileCleanup.deleteOnRollback(stored.key());
        Guard.requireState(stored.sha256().equals(document.getSha256()), "dispute.quarantine_hash_mismatch",
                "O documento mudou na quarentena");
        DisputeEvidence evidence = evidences.save(DisputeEvidence.fromQuarantine(dispute, stored.key(),
                document.getFileName(), document.getContentType(), stored.size(), stored.sha256(),
                document.getCreditorResponseId()));
        auditTrail.record(AuditEntry.of("dispute.evidence_attached", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("evidenceId", evidence.getId())
                .created("fileName", evidence.getFileName())
                .created("sha256", evidence.getSha256())
                .meta("size", evidence.getSize())
                .meta("creditorResponseId", document.getCreditorResponseId())
                .meta("quarantineId", document.getId()));
        return evidence;
    }

    /** @param creditorResponseId resposta do credor que trouxe o documento (nulo = anexado pela assessoria) */
    DisputeEvidence store(Dispute dispute, EvidenceUpload upload, UUID creditorResponseId) {
        dispute.requireOpenForEvidence();
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "dispute.evidence_empty",
                "Arquivo de evidência vazio");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "dispute.evidence_too_large",
                "Arquivo de evidência excede o tamanho máximo");
        String contentType = upload.contentType() == null ? ""
                : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        Guard.require(EVIDENCE_TYPES.contains(contentType), "dispute.evidence_type_invalid",
                "Tipo de arquivo não aceito como evidência");
        if (evidences.countByDisputeId(dispute.getId()) >= DisputeEvidence.MAX_PER_DISPUTE) {
            throw new ConflictException("dispute.evidence_limit", "Limite de %d evidências por contestação"
                    .formatted(DisputeEvidence.MAX_PER_DISPUTE));
        }
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, upload.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        DisputeEvidence evidence = evidences.save(new DisputeEvidence(dispute, stored.key(), stored.originalName(),
                contentType, stored.size(), stored.sha256(), creditorResponseId));
        auditTrail.record(AuditEntry.of("dispute.evidence_attached", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("evidenceId", evidence.getId())
                .created("fileName", evidence.getFileName())
                .created("sha256", evidence.getSha256())
                .meta("size", evidence.getSize())
                .meta("creditorResponseId", creditorResponseId));
        return evidence;
    }
}
