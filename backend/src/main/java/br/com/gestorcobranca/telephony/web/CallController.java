package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallFilter;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallbackInput;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.PromiseInput;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.RecordingFile;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Chamadas: clique para ligar da estação de cobrança (201 com {@code QUEUED}, ou
 * {@code FAILED/CONTACT_DENIED} com motivos e próximo instante permitido quando o porteiro já nega),
 * consulta, encerramento, tabulação (pós-atendimento) e gravação (finalidade obrigatória).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony/calls")
class CallController {

    private final CallUseCases calls;
    private final CurrentActor currentActor;

    CallController(CallUseCases calls, CurrentActor currentActor) {
        this.calls = calls;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    CallView clickToCall(@RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey,
                         @Valid @RequestBody ClickToCallRequest request) {
        return calls.clickToCall(new ClickToCallCommand(request.caseId(), request.contactId()), idempotencyKey);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TELEPHONY_USE') or hasAuthority('TELEPHONY_SUPERVISE')")
    PageResponse<CallView> search(@RequestParam(required = false) UUID caseId,
                                  @RequestParam(required = false) UUID debtorId,
                                  @RequestParam(required = false) UUID creditorId,
                                  @RequestParam(required = false) String operator,
                                  @RequestParam(required = false) UUID campaignId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String result,
                                  @RequestParam(required = false) String direction,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        UUID operatorId = operator == null || operator.isBlank() ? null
                : "me".equalsIgnoreCase(operator) ? currentActor.actorId() : parseId(operator);
        return calls.search(new CallFilter(caseId, debtorId, creditorId, operatorId, campaignId, status, result, direction,
                from, to), PageQuery.of(page, size));
    }

    @GetMapping("/awaiting-tabulation")
    @PreAuthorize("hasAuthority('TELEPHONY_USE')")
    List<CallView> awaitingTabulation() {
        return calls.awaitingTabulation();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TELEPHONY_USE') or hasAuthority('TELEPHONY_SUPERVISE')")
    CallView get(@PathVariable UUID id) {
        return calls.get(id);
    }

    @PostMapping("/{id}/hangup")
    @PreAuthorize("hasAuthority('TELEPHONY_USE') or hasAuthority('TELEPHONY_SUPERVISE')")
    CallView hangup(@PathVariable UUID id) {
        return calls.hangup(id);
    }

    @PostMapping("/{id}/tabulation")
    @PreAuthorize("hasAuthority('TELEPHONY_USE') and hasAuthority('INTERACTION_REGISTER')")
    CallView tabulate(@PathVariable UUID id, @Valid @RequestBody TabulationRequest request) {
        return calls.tabulate(id, new TabulateCommand(request.tabulationCode(), request.note(),
                request.promise() == null ? null : new PromiseInput(request.promise().amount(), request.promise().dueDate()),
                request.callback() == null ? null : new CallbackInput(request.callback().dueAt(),
                        request.callback().assigneeUserId(), request.callback().note()),
                request.caseId()));
    }

    /** Gravação para ouvir/baixar: registra {@code FILE_DOWNLOADED} com a finalidade no log de acesso. */
    @GetMapping("/{id}/recording")
    @PreAuthorize("hasAuthority('CALL_RECORDING_LISTEN')")
    ResponseEntity<InputStreamResource> recording(@PathVariable UUID id, @RequestParam String purpose) {
        RecordingFile file = calls.openRecording(id, purpose);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()));
        if (file.size() >= 0) {
            builder.contentLength(file.size());
        }
        return builder.body(new InputStreamResource(file.content()));
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("call.operator_invalid", "Operador inválido: use um id ou 'me'");
        }
    }

    record ClickToCallRequest(@NotNull UUID caseId, UUID contactId) {
    }

    record TabulationRequest(@NotBlank @Size(max = 60) String tabulationCode, @Size(max = 4000) String note,
                             @Valid PromiseRequest promise, @Valid CallbackRequest callback, UUID caseId) {
    }

    record PromiseRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull LocalDate dueDate) {
    }

    record CallbackRequest(@NotNull LocalDateTime dueAt, UUID assigneeUserId, @Size(max = 1000) String note) {
    }
}
