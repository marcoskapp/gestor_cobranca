package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.TitleDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Títulos do credor (módulo debt). Mudanças de situação usam os mesmos casos de uso das telas: histórico preservado,
 * auditoria, evento {@code TitleStatusChanged}. Baixa por pagamento não é feita aqui: informe o pagamento
 * ({@code POST /payments}) — a baixa exige pagamento com evidência (RB06).
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/titles")
@Tag(name = "Títulos", description = "Consulta, sincronização e ciclo de vida dos títulos")
class TitleController {

    private final DebtTitleUseCases titles;
    private final PublicApiSupport support;

    TitleController(DebtTitleUseCases titles, PublicApiSupport support) {
        this.titles = titles;
        this.support = support;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_titles:read')")
    @Operation(summary = "Títulos (sincronização incremental)", description = "Ordem crescente de atualização. Para "
            + "sincronizar: guarde o `nextCursor` da última página e continue dele; ou filtre por `updatedSince`.")
    ListResponse<TitleDto> list(@RequestParam(required = false) UUID portfolioId,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(titles.changes(support.creditorId(), portfolioId, support.query(updatedSince, cursor, limit)),
                TitleDto::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_titles:read')")
    @Operation(summary = "Título")
    ResponseEntity<TitleDto> get(@PathVariable UUID id) {
        TitleDto dto = TitleDto.of(own(id));
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    @GetMapping("/by-external-id/{externalId}")
    @PreAuthorize("hasAuthority('SCOPE_titles:read')")
    @Operation(summary = "Título pelo identificador do credor", description = "O `id_titulo` informado na carga.")
    ResponseEntity<TitleDto> byExternalId(@PathVariable @Size(max = 80) String externalId) {
        TitleDto dto = TitleDto.of(titles.findByExternalId(support.creditorId(), externalId)
                .orElseThrow(() -> new ResourceNotFoundException("debt_title", externalId)));
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('SCOPE_titles:write')")
    @Operation(summary = "Suspender título", description = "Tira o título da cobrança (pedido do credor), com motivo.")
    ResponseEntity<TitleDto> suspend(@PathVariable UUID id, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @Valid @RequestBody TitleReasonRequest r, HttpServletRequest http) {
        own(id);
        return done(titles.suspend(id, r.reason(), PublicApiSupport.ifMatch(ifMatch)), http);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('SCOPE_titles:write')")
    @Operation(summary = "Reativar título suspenso")
    ResponseEntity<TitleDto> reactivate(@PathVariable UUID id,
                                        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        HttpServletRequest http) {
        own(id);
        return done(titles.reactivate(id, PublicApiSupport.ifMatch(ifMatch)), http);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_titles:write')")
    @Operation(summary = "Cancelar título", description = "Cancelamento pelo credor (ex.: título indevido), com motivo.")
    ResponseEntity<TitleDto> cancel(@PathVariable UUID id, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                    @Valid @RequestBody TitleReasonRequest r, HttpServletRequest http) {
        own(id);
        return done(titles.cancel(id, r.reason(), PublicApiSupport.ifMatch(ifMatch)), http);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('SCOPE_titles:write')")
    @Operation(summary = "Devolver título ao credor", description = "Encerra a titularidade da assessoria, com motivo.")
    ResponseEntity<TitleDto> giveBack(@PathVariable UUID id, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                      @Valid @RequestBody TitleReasonRequest r, HttpServletRequest http) {
        own(id);
        return done(titles.returnToCreditor(id, r.reason(), PublicApiSupport.ifMatch(ifMatch)), http);
    }

    private ResponseEntity<TitleDto> done(TitleView view, HttpServletRequest http) {
        UsageRecords.add(http, 1);
        TitleDto dto = TitleDto.of(view);
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    private TitleView own(UUID id) {
        TitleView view = titles.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("debt_title", id);
        }
        return view;
    }

    record TitleReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
