package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.CreditorPending;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.NoticeSearch;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.NoticeSummary;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.NoticeView;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.RegisterBatchCommand;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.RegisterNotifiedCommand;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases.RemittanceView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Avisos ao credor da anonimização do titular (LGPD art. 18 §6º). Mesma permissão das solicitações de titulares;
 * acesso a todos os credores exigido no serviço.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/creditor-erasure-notices")
class CreditorErasureNoticeController {

    private final CreditorErasureNoticeUseCases notices;

    CreditorErasureNoticeController(CreditorErasureNoticeUseCases notices) {
        this.notices = notices;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    PageResponse<NoticeSummary> search(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) UUID originId,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return notices.search(new NoticeSearch(status, creditorId, originId), PageQuery.of(page, size));
    }

    /** Credores com avisos pendentes (decisão 1484). */
    @GetMapping("/pending-by-creditor")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    List<CreditorPending> pendingByCreditor() {
        return notices.pendingByCreditor();
    }

    /** Remessa: os avisos pendentes do credor num texto só. */
    @GetMapping("/remittance")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RemittanceView remittance(@RequestParam UUID creditorId) {
        return notices.remittance(creditorId);
    }

    /** Registro do aviso de vários de uma vez (mesmo credor). */
    @PostMapping("/notified")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    List<NoticeSummary> registerNotifiedBatch(@Valid @RequestBody BatchNotifiedRequest request) {
        return notices.registerNotifiedBatch(new RegisterBatchCommand(request.noticeIds(), request.notifiedAt(),
                request.channel()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    NoticeView get(@PathVariable UUID id) {
        return notices.get(id);
    }

    @PostMapping("/{id}/notified")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    NoticeView registerNotified(@PathVariable UUID id, @Valid @RequestBody NotifiedRequest request) {
        return notices.registerNotified(id, new RegisterNotifiedCommand(request.notifiedAt(), request.channel()));
    }

    record BatchNotifiedRequest(@NotEmpty @Size(max = CreditorErasureNoticeUseCases.MAX_REMITTANCE) List<@NotNull UUID> noticeIds,
                                @NotNull Instant notifiedAt, @NotBlank @Size(max = 20) String channel) {
    }

    record NotifiedRequest(@NotNull Instant notifiedAt, @NotBlank @Size(max = 20) String channel) {
    }
}
