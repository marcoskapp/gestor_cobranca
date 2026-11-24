package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.AcknowledgeCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.ContestCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.FileContent;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.OpenPeriod;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.StatementDetail;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.StatementItem;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Prestação de contas no portal do credor (ADR 0011, fatia 4). Só o ator {@code CREDITOR} (filtro e
 * {@code @PreAuthorize}); permissão e credor conferidos no caso de uso. Demonstrativo de outro credor ou não enviado → 404.
 */
@RestController
@RequestMapping(ApiPaths.CREDITOR + "/statements")
@PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
class CreditorStatementController {

    private final CreditorStatementUseCases statements;

    CreditorStatementController(CreditorStatementUseCases statements) {
        this.statements = statements;
    }

    @GetMapping
    List<StatementItem> list() {
        return statements.statements();
    }

    @GetMapping("/open-period")
    OpenPeriod openPeriod() {
        return statements.openPeriod();
    }

    @GetMapping("/{id}")
    StatementDetail get(@PathVariable UUID id) {
        return statements.statement(id);
    }

    /** Download com registro no log de acesso; o SHA-256 vai no cabeçalho para conferência. */
    @GetMapping("/{id}/files/{fileId}/content")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID fileId) {
        FileContent file = statements.download(id, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @PostMapping("/{id}/acknowledge")
    StatementDetail acknowledge(@PathVariable UUID id, @Valid @RequestBody AcknowledgeRequest request,
                                HttpServletRequest http) {
        return statements.acknowledge(id, new AcknowledgeCommand(request.code(), request.note(), request.fileSha256(),
                http.getRemoteAddr()));
    }

    @PostMapping("/{id}/contest")
    StatementDetail contest(@PathVariable UUID id, @Valid @RequestBody ContestRequest request, HttpServletRequest http) {
        return statements.contest(id, new ContestCommand(request.reason(), http.getRemoteAddr()));
    }

    record AcknowledgeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code, @Size(max = 2000) String note,
                              @Size(max = 100) List<@Pattern(regexp = "[0-9a-f]{64}") String> fileSha256) {
    }

    record ContestRequest(@NotBlank @Size(max = 2000) String reason) {
    }
}
