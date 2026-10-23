package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases.IndexCommand;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases.IndexView;
import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases.RateInput;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import java.time.YearMonth;
import java.util.List;

/** Tabela manual de índices de correção monetária (variações mensais por competência). */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation/indexes")
@Tag(name = "Negotiation — índices", description = "Índices de correção monetária da assessoria")
class MonetaryIndexController {

    private final MonetaryIndexUseCases indexes;

    MonetaryIndexController(MonetaryIndexUseCases indexes) {
        this.indexes = indexes;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    List<IndexView> list() {
        return indexes.list();
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    IndexView get(@PathVariable String code) {
        return indexes.get(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    IndexView create(@Valid @RequestBody IndexRequest request) {
        return indexes.create(new IndexCommand(request.code(), request.name(), request.source()));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    IndexView update(@PathVariable String code, @Valid @RequestBody UpdateRequest request) {
        return indexes.update(code, new IndexCommand(code, request.name(), request.source()));
    }

    @PutMapping("/{code}/rates")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Cria ou corrige variações mensais (auditadas)")
    IndexView putRates(@PathVariable String code, @Valid @RequestBody RatesRequest request) {
        return indexes.putRates(code, request.rates().stream().map(r -> new RateInput(r.competence(), r.ratePercent()))
                .toList());
    }

    record IndexRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,20}") String code, @NotBlank @Size(max = 120) String name,
                        @Size(max = 200) String source) {
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 200) String source) {
    }

    record RatesRequest(@NotEmpty @Size(max = 600) List<@Valid RateRequest> rates) {
    }

    record RateRequest(@NotNull YearMonth competence, @NotNull BigDecimal ratePercent) {
    }
}
