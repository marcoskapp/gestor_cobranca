package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases.ConfirmCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases.MailReplyView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceContent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Respostas do credor recebidas por e-mail na contestação: lista, confirmação da posição, descarte, quarentena da
 * gestão e download do original (.eml, sem cache e com log de acesso).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class DisputeMailReplyController {

    private static final String WORK = "hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK')";
    private static final String READ = "hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK') or hasAuthority('CASE_VIEW')";

    private final DisputeMailReplyUseCases replies;

    DisputeMailReplyController(DisputeMailReplyUseCases replies) {
        this.replies = replies;
    }

    @GetMapping("/disputes/{id}/mail-replies")
    @PreAuthorize(READ)
    List<MailReplyView> list(@PathVariable UUID id) {
        return replies.list(id);
    }

    @PostMapping("/disputes/{id}/mail-replies/{replyId}/confirm")
    @PreAuthorize(WORK)
    CreditorResponseView confirm(@PathVariable UUID id, @PathVariable UUID replyId,
                                 @Valid @RequestBody ConfirmRequest request) {
        return replies.confirm(id, replyId, new ConfirmCommand(request.position(), request.note()));
    }

    @PostMapping("/disputes/{id}/mail-replies/{replyId}/dismiss")
    @PreAuthorize(WORK)
    MailReplyView dismiss(@PathVariable UUID id, @PathVariable UUID replyId, @Valid @RequestBody NoteRequest request) {
        return replies.dismiss(id, replyId, request.note());
    }

    @PostMapping("/disputes/{id}/mail-replies/{replyId}/release")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    MailReplyView release(@PathVariable UUID id, @PathVariable UUID replyId, @Valid @RequestBody NoteRequest request) {
        return replies.release(id, replyId, request.note());
    }

    @GetMapping("/dispute-mail-quarantine")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    PageResponse<MailReplyView> quarantined(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return replies.quarantined(PageQuery.of(page, size));
    }

    /** Original como chegou (cadeia de custódia): download sem cache, registra FILE_DOWNLOADED. */
    @GetMapping("/disputes/{id}/mail-replies/{replyId}/original")
    @PreAuthorize(WORK)
    ResponseEntity<InputStreamResource> original(@PathVariable UUID id, @PathVariable UUID replyId) {
        EvidenceContent file = replies.original(id, replyId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    record ConfirmRequest(@NotBlank @Pattern(regexp = "CONFIRMS_DEBT|ACCEPTS_CLAIM|PARTIAL|INFORMATION") String position,
                          @NotBlank @Size(max = 4000) String note) {
    }

    record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }
}
