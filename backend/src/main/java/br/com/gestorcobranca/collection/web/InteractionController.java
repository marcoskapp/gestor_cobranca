package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.InteractionView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Interações e tabulação do caso. Datas do retorno no fuso de operação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/cases/{caseId}/interactions")
class InteractionController {

    private final InteractionUseCases interactions;

    InteractionController(InteractionUseCases interactions) {
        this.interactions = interactions;
    }

    /** 201 com a interação registrada; 200 quando a referência externa já havia sido registrada. */
    @PostMapping
    @PreAuthorize("hasAuthority('INTERACTION_REGISTER')")
    ResponseEntity<RecordResult> record(@PathVariable UUID caseId, @Valid @RequestBody InteractionRequest request) {
        RecordResult result = interactions.record(caseId, request.toCommand());
        return ResponseEntity.status(result.duplicate() ? 200 : 201).body(result);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    PageResponse<InteractionView> list(@PathVariable UUID caseId, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return interactions.list(caseId, PageQuery.of(page, size));
    }

    record InteractionRequest(@NotBlank @Size(max = 20) String channel,
                              @NotBlank @Size(max = 10) String direction,
                              UUID tabulationId,
                              @Size(max = 40) String tabulationCode,
                              @Size(max = 2000) String note,
                              UUID contactId,
                              Instant startedAt,
                              Instant endedAt,
                              @Valid PromiseRequest promise,
                              @Valid CallbackRequest callback,
                              UUID completesTaskId,
                              @Size(max = 20) String source,
                              @Size(max = 120) String externalRef) {

        RecordInteractionCommand toCommand() {
            return new RecordInteractionCommand(channel, direction, tabulationId, tabulationCode, note, contactId,
                    startedAt, endedAt, promise == null ? null : new PromiseInput(promise.amount(), promise.dueDate()),
                    callback == null ? null : new CallbackInput(callback.dueAt(), callback.assigneeUserId(), callback.note()),
                    completesTaskId, source, externalRef);
        }
    }

    record PromiseRequest(@NotNull @DecimalMin(value = "0.01") BigDecimal amount, @NotNull LocalDate dueDate) {
    }

    /** @param dueAt data e hora no fuso de operação (America/Sao_Paulo), sem deslocamento */
    record CallbackRequest(@NotNull LocalDateTime dueAt, UUID assigneeUserId, @Size(max = 1000) String note) {
    }
}
