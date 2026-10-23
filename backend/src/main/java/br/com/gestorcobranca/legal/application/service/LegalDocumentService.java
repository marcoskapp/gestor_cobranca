package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases;
import br.com.gestorcobranca.legal.domain.model.DocumentKind;
import br.com.gestorcobranca.legal.domain.model.DocumentLink;
import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.CaseMovementRepository;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import br.com.gestorcobranca.legal.domain.repository.HearingRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalExpenseRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class LegalDocumentService implements LegalDocumentUseCases {

    static final String ENTITY = "legal_document";

    private final LegalDocumentRepository documents;
    private final CreditorAuthorizationRepository authorizations;
    private final ExtrajudicialMeasureRepository measures;
    private final LawsuitRepository lawsuits;
    private final CaseMovementRepository movements;
    private final HearingRepository hearings;
    private final LegalExpenseRepository expenses;
    private final DocumentStore store;
    private final LegalAccess access;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;

    LegalDocumentService(LegalDocumentRepository documents, CreditorAuthorizationRepository authorizations,
                         ExtrajudicialMeasureRepository measures, LawsuitRepository lawsuits,
                         CaseMovementRepository movements, HearingRepository hearings, LegalExpenseRepository expenses,
                         DocumentStore store, LegalAccess access, AuditTrail auditTrail, AccessLogger accessLogger) {
        this.documents = documents;
        this.authorizations = authorizations;
        this.measures = measures;
        this.lawsuits = lawsuits;
        this.movements = movements;
        this.hearings = hearings;
        this.expenses = expenses;
        this.store = store;
        this.access = access;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
    }

    @Override
    public DocumentView upload(UUID matterId, UploadCommand command, FileUpload file) {
        LegalMatter m = access.forCommand(matterId);
        DocumentKind kind = LegalViews.parse(DocumentKind.class, command.kind(), "legal_document.kind_invalid",
                "Natureza do documento");
        DocumentLink link = command.linkType() == null || command.linkType().isBlank() ? DocumentLink.MATTER
                : LegalViews.parse(DocumentLink.class, command.linkType(), "legal_document.link_invalid", "Vínculo");
        requireLink(m.getId(), link, command.linkId());
        StoredFile stored = store.store(file.fileName(), file.content(), file.size());
        LegalDocument d = documents.save(new LegalDocument(m.getId(), m.getCreditorId(), kind, link,
                link == DocumentLink.MATTER ? null : command.linkId(), command.description(), stored.key(),
                stored.originalName(), stored.contentType(), stored.size(), stored.sha256()));
        auditTrail.record(AuditEntry.of("legal_document.uploaded", ENTITY, d.getId()).creditor(m.getCreditorId())
                .created("matterId", m.getId()).created("kind", d.getKind()).created("linkType", d.getLinkType())
                .created("linkId", d.getLinkId()).created("fileName", d.getFileName()).created("sha256", d.getSha256())
                .created("size", d.getSizeBytes()));
        return LegalViews.document(d);
    }

    private void requireLink(UUID matterId, DocumentLink link, UUID id) {
        if (link == DocumentLink.MATTER) {
            return;
        }
        boolean ok = id != null && switch (link) {
            case AUTHORIZATION -> authorizations.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case MEASURE -> measures.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case LAWSUIT -> lawsuits.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case MOVEMENT -> movements.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case HEARING -> hearings.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case EXPENSE -> expenses.findById(id).filter(a -> a.getMatterId().equals(matterId)).isPresent();
            case MATTER -> true;
        };
        if (!ok) {
            throw new BusinessRuleException("legal_document.link_invalid", "Registro vinculado não pertence ao caso jurídico");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentView> list(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return documents.findByMatterIdOrderByCreatedAtDesc(m.getId()).stream().map(LegalViews::document).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FileDownload download(UUID documentId) {
        LegalDocument d = documents.findById(documentId).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, documentId));
        access.visible(d.getMatterId());
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, access.actor(),
                Map.of("resource", ENTITY, "resourceId", d.getId().toString(), "matterId", d.getMatterId().toString(),
                        "kind", d.getKind().name())));
        return new FileDownload(d.getFileName(), d.getContentType(), d.getSizeBytes(), store.open(d.getStorageKey()));
    }
}
