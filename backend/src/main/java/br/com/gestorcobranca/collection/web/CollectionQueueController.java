package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueSummary;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Filas de cobrança configuráveis. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/queues")
class CollectionQueueController {

    private final QueueUseCases queues;

    CollectionQueueController(QueueUseCases queues) {
        this.queues = queues;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW') or hasAuthority('QUEUE_MANAGE')")
    List<QueueView> list() {
        return queues.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CASE_VIEW') or hasAuthority('QUEUE_MANAGE')")
    QueueView get(@PathVariable UUID id) {
        return queues.get(id);
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('CASE_ASSIGN') or hasAuthority('QUEUE_MANAGE')")
    QueueSummary summary(@PathVariable UUID id) {
        return queues.summary(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    QueueView create(@Valid @RequestBody QueueRequest request) {
        return queues.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    QueueView update(@PathVariable UUID id, @Valid @RequestBody QueueRequest request) {
        return queues.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    QueueView activate(@PathVariable UUID id) {
        return queues.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    QueueView deactivate(@PathVariable UUID id) {
        return queues.deactivate(id);
    }

    /** Reprocessa o roteamento de todos os casos ativos em segundo plano. */
    @PostMapping("/reroute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    void reroute() {
        queues.reroute();
    }

    record QueueRequest(@Size(max = 40) String code,
                        @NotBlank @Size(max = 120) String name,
                        @Size(max = 500) String description,
                        @NotNull @Min(0) Integer priority,
                        UUID creditorId,
                        UUID portfolioId,
                        @Min(0) Integer minDaysOverdue,
                        @Min(0) Integer maxDaysOverdue,
                        @DecimalMin("0") BigDecimal minBalance,
                        @DecimalMin("0") BigDecimal maxBalance,
                        Integer minScore,
                        Integer maxScore,
                        @Size(max = 30) String ordering,
                        @Size(max = 20) String strategy,
                        @Min(1) Integer maxCasesPerUser,
                        @Size(max = 50) Set<UUID> teamIds) {

        QueueCommand toCommand() {
            return new QueueCommand(code, name, description, priority, creditorId, portfolioId, minDaysOverdue,
                    maxDaysOverdue, minBalance, maxBalance, minScore, maxScore, ordering, strategy, maxCasesPerUser,
                    teamIds);
        }
    }
}
