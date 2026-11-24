package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.DisputeDetail;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.DisputeItem;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.DocumentContent;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.RespondCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.Upload;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contestações no portal do credor (ADR 0011, fatia 5). Só o ator {@code CREDITOR}; permissão e credor conferidos no
 * caso de uso. Contestação de outro credor ou sem pedido ao credor → 404.
 */
@RestController
@RequestMapping(ApiPaths.CREDITOR + "/disputes")
@PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
class CreditorDisputeController {

    /** Documentos por resposta e tamanho de cada um (os mesmos da API pública; conferidos de novo no caso de uso). */
    static final int MAX_DOCUMENTS = 5;
    static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;

    private final CreditorDisputePortalUseCases disputes;
    private final CreditorUploadLimiter uploadLimiter;

    CreditorDisputeController(CreditorDisputePortalUseCases disputes, CreditorUploadLimiter uploadLimiter) {
        this.disputes = disputes;
        this.uploadLimiter = uploadLimiter;
    }

    @GetMapping
    List<DisputeItem> list() {
        return disputes.disputes();
    }

    @GetMapping("/{id}")
    DisputeDetail get(@PathVariable UUID id) {
        return disputes.dispute(id);
    }

    /** Download com registro no log de acesso; o SHA-256 vai no cabeçalho para conferência. */
    @GetMapping("/{id}/documents/{documentId}/content")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID documentId) {
        DocumentContent file = disputes.download(id, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    /**
     * Resposta ao pedido da assessoria. Partes: {@code response} (JSON) e {@code files} (até 5 PDF, PNG ou JPEG de até
     * 10 MB cada; o tipo é conferido pelo conteúdo e os documentos passam pela quarentena antes de chegar à assessoria).
     */
    @PostMapping(path = "/{id}/responses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    DisputeDetail respond(@PathVariable UUID id, @Valid @RequestPart("response") RespondRequest request,
                          @RequestPart(name = "files", required = false) List<MultipartFile> files,
                          HttpServletResponse http) {
        List<MultipartFile> parts = files == null ? List.of() : files.stream().filter(Objects::nonNull).toList();
        if (!parts.isEmpty()) {
            // conta a tentativa antes de qualquer checagem: envio recusado também gasta o limite (SEG-F01)
            uploadLimiter.acquire("dispute_response", http);
        }
        if (parts.stream().anyMatch(MultipartFile::isEmpty)) {
            throw new BusinessRuleException("dispute.evidence_empty", "Um dos documentos está vazio");
        }
        if (parts.size() > MAX_DOCUMENTS) {
            throw new BusinessRuleException("dispute_response.too_many_documents",
                    "No máximo %d documentos por resposta".formatted(MAX_DOCUMENTS));
        }
        if (parts.stream().anyMatch(f -> f.getSize() > MAX_DOCUMENT_BYTES)) {
            throw new BusinessRuleException("dispute.evidence_too_large", "Cada documento pode ter até 10 MB");
        }
        List<Upload> uploads = new ArrayList<>();
        for (MultipartFile f : parts) {
            try {
                uploads.add(new Upload(f.getOriginalFilename(), f.getContentType(), f.getSize(), f.getInputStream()));
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
            }
        }
        return disputes.respond(id, new RespondCommand(request.requestId(), request.position(), request.note()), uploads);
    }

    record RespondRequest(@NotNull UUID requestId,
                          @NotBlank @Pattern(regexp = "CONFIRMS_DEBT|ACCEPTS_CLAIM|PARTIAL|INFORMATION") String position,
                          @NotBlank @Size(max = 4000) String note) {
    }
}
