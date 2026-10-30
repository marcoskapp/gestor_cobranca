package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.web.v1.BillingModels.BillingPositionDto;
import br.com.gestorcobranca.publicapi.web.v1.BillingModels.StatementDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Faturamento da própria API pública: posição do ciclo em andamento e extratos fechados do credor do token. */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/billing")
@Tag(name = "Faturamento da API", description = "Consumo e extratos da API pública do próprio credor")
class BillingController {

    private final PublicApiBillingUseCases billing;
    private final PublicApiSupport support;

    BillingController(PublicApiBillingUseCases billing, PublicApiSupport support) {
        this.billing = billing;
        this.support = support;
    }

    @GetMapping("/position")
    @PreAuthorize("hasAuthority('SCOPE_billing:read')")
    @Operation(summary = "Ciclo de consumo em andamento: consumo faturável, valor até agora, franquias e projeção")
    BillingPositionDto position() {
        return BillingPositionDto.of(billing.position(support.creditorId()));
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('SCOPE_billing:read')")
    @Operation(summary = "Extratos fechados (mais recentes primeiro; todas as revisões)")
    ListResponse<StatementDto> statements(@RequestParam(required = false) @Min(1) @Max(120) Integer limit) {
        int size = limit == null ? 24 : limit;
        PageResponse<PublicApiBillingUseCases.StatementView> page = billing.statements(support.creditorId(), null,
                PageQuery.of(0, size));
        return new ListResponse<>(page.content().stream().map(StatementDto::of).toList(), null,
                page.totalElements() > page.content().size());
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize("hasAuthority('SCOPE_billing:read')")
    @Operation(summary = "Extrato fechado")
    StatementDto statement(@PathVariable UUID id) {
        PublicApiBillingUseCases.StatementView s = billing.statement(id);
        if (!s.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("usage_statement", id);
        }
        return StatementDto.of(s);
    }
}
