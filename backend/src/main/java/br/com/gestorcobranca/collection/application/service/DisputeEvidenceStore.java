package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
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
    private final FileStorage fileStorage;
    private final DisputeFileCleanup fileCleanup;
    private final AuditTrail auditTrail;
    private final CollectionProperties properties;

    DisputeEvidenceStore(DisputeEvidenceRepository evidences, FileStorage fileStorage, DisputeFileCleanup fileCleanup,
                         AuditTrail auditTrail, CollectionProperties properties) {
        this.evidences = evidences;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.auditTrail = auditTrail;
        this.properties = properties;
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
