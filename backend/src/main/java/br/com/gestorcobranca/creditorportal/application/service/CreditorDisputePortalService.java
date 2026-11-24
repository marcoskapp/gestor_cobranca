package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDisputeDetail;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDisputeDocument;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDisputeResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDisputeSummary;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDocumentContent;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDocumentUpload;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Contestações no portal do credor: traduz para a lista de campos permitidos do portal. */
@Service
@Transactional
class CreditorDisputePortalService implements CreditorDisputePortalUseCases {

    private final CreditorDisputeUseCases collection;

    CreditorDisputePortalService(CreditorDisputeUseCases collection) {
        this.collection = collection;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeItem> disputes() {
        return collection.disputes().stream().map(CreditorDisputePortalService::item).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeDetail dispute(UUID disputeId) {
        return detail(collection.dispute(disputeId));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentContent download(UUID disputeId, UUID documentId) {
        CreditorDocumentContent f = collection.download(disputeId, documentId);
        return new DocumentContent(f.fileName(), f.contentType(), f.size(), f.sha256(), f.content());
    }

    @Override
    public DisputeDetail respond(UUID disputeId, RespondCommand c, List<Upload> documents) {
        List<CreditorDocumentUpload> uploads = documents == null ? List.of() : documents.stream()
                .map(u -> new CreditorDocumentUpload(u.fileName(), u.contentType(), u.size(), u.content())).toList();
        return detail(collection.respond(disputeId, c == null ? null
                : new CreditorDisputeResponseCommand(c.requestId(), c.position(), c.note()), uploads));
    }

    private static DisputeItem item(CreditorDisputeSummary s) {
        return new DisputeItem(s.id(), s.protocol(), s.type(), s.situation(), s.outcome(), s.openedAt(),
                s.disputedAmount(), s.titleCount(), s.requestedDueDate(), s.overdue(), s.lastResponseAt(),
                s.closedAt());
    }

    private static DocumentItem document(CreditorDisputeDocument d) {
        return new DocumentItem(d.id(), d.fileName(), d.size(), d.sha256());
    }

    private static DisputeDetail detail(CreditorDisputeDetail d) {
        return new DisputeDetail(item(d.summary()),
                d.titles().stream().map(t -> new TitleItem(t.externalId(), t.installmentNumber(), t.dueDate(),
                        t.openBalance())).toList(),
                d.requests().stream().map(r -> new RequestItem(r.id(), r.sequenceNumber(), r.sentAt(),
                        r.requestedDueDate(), r.awaiting(), r.overdue(), r.subject(), r.body(),
                        r.documents().stream().map(CreditorDisputePortalService::document).toList())).toList(),
                d.responses().stream().map(r -> new ResponseItem(r.id(), r.requestId(), r.position(), r.origin(),
                        r.note(), r.receivedAt(),
                        r.documents().stream().map(CreditorDisputePortalService::document).toList(),
                        r.documentsInVerification(), r.documentsRejected())).toList());
    }
}
