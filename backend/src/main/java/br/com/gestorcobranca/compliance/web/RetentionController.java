package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionPolicyView;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionRunItemView;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionRunView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Políticas de retenção, simulação e execução do descarte. */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/retention")
class RetentionController {

    private final RetentionUseCases retention;

    RetentionController(RetentionUseCases retention) {
        this.retention = retention;
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    List<RetentionPolicyView> policies() {
        return retention.listPolicies();
    }

    @PutMapping("/policies/{category}")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    RetentionPolicyView configure(@PathVariable String category, @Valid @RequestBody PolicyRequest request) {
        return retention.configure(category, request.retentionMonths(), request.enabled());
    }

    /** {@code dryRun=true} (padrão) só gera o relatório dos elegíveis. */
    @PostMapping("/policies/{category}/run")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    RetentionRunView run(@PathVariable String category, @RequestParam(defaultValue = "true") boolean dryRun) {
        return retention.run(category, dryRun);
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    PageResponse<RetentionRunView> runs(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return retention.listRuns(PageQuery.of(page, size));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    RetentionRunView run(@PathVariable UUID id) {
        return retention.getRun(id);
    }

    @GetMapping("/runs/{id}/items")
    @PreAuthorize("hasAuthority('RETENTION_MANAGE')")
    PageResponse<RetentionRunItemView> items(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return retention.runItems(id, PageQuery.of(page, size));
    }

    record PolicyRequest(@Min(1) @Max(1200) int retentionMonths, boolean enabled) {
    }
}
