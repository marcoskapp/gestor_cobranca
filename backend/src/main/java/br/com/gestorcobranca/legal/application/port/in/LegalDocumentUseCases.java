package br.com.gestorcobranca.legal.application.port.in;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Documentos e evidências do caso jurídico no armazenamento do tenant (PDF, PNG ou JPEG,
 * conferidos pelo conteúdo, com SHA-256). O download registra {@code FILE_DOWNLOADED} no log de
 * acesso. Append-only: documentos não são alterados nem removidos pela aplicação.
 */
public interface LegalDocumentUseCases {

    DocumentView upload(UUID matterId, UploadCommand command, FileUpload file);

    List<DocumentView> list(UUID matterId);

    FileDownload download(UUID documentId);

    /**
     * @param kind     CONTRACT, DEBT_STATEMENT, NOTICE, AUTHORIZATION_EVIDENCE, POWER_OF_ATTORNEY, PETITION,
     *                 COURT_DECISION, PROTEST_INSTRUMENT, CREDIT_BUREAU_RECEIPT, REGISTRY_CERTIFICATE, RECEIPT,
     *                 HEARING_RECORD ou OTHER
     * @param linkType MATTER (padrão), AUTHORIZATION, MEASURE, LAWSUIT, MOVEMENT, HEARING ou EXPENSE
     */
    record UploadCommand(String kind, String linkType, UUID linkId, String description) {
    }

    record FileUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record FileDownload(String fileName, String contentType, long size, InputStream content) {
    }

    record DocumentView(UUID id, UUID matterId, String kind, String linkType, UUID linkId, String description,
                        String fileName, String contentType, long size, String sha256, Instant uploadedAt,
                        UUID uploadedBy) {
    }
}
