package br.com.gestorcobranca.incentive.web;

import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.SimulationView;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Planos de comissão versionados ({@code DRAFT → PUBLISHED → RETIRED}), metas por período e
 * simulação. As regras são tipadas ({@link PlanRules}) e validadas integralmente no domínio
 * (erros 422 com código {@code plan.rules.*}).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/incentive")
@Tag(name = "Incentive — planos", description = "Planos de comissão, metas e simulação")
class CommissionPlanController {

    private final CommissionPlanUseCases plans;

    CommissionPlanController(CommissionPlanUseCases plans) {
        this.plans = plans;
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    List<PlanView> list() {
        return plans.list();
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    PlanView get(@PathVariable UUID id) {
        return plans.get(id);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Cria um plano (versão 1, rascunho)")
    PlanView create(@Valid @RequestBody PlanRequest request) {
        return plans.createDraft(request.toCommand());
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Altera um rascunho (versões publicadas são imutáveis)")
    PlanView update(@PathVariable UUID id, @Valid @RequestBody PlanRequest request) {
        return plans.updateDraft(id, request.toCommand());
    }

    @PostMapping("/plans/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Cria nova versão em rascunho a partir desta")
    PlanView newVersion(@PathVariable UUID id) {
        return plans.newVersion(id);
    }

    @PostMapping("/plans/{id}/publish")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Publica a versão a partir do dia 1 de um mês (substitui a versão vigente)")
    PlanView publish(@PathVariable UUID id, @Valid @RequestBody PublishRequest request) {
        return plans.publish(id, request.effectiveFrom());
    }

    @PostMapping("/plans/{id}/retire")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    PlanView retire(@PathVariable UUID id, @Valid @RequestBody RetireRequest request) {
        return plans.retire(id, request.effectiveTo());
    }

    @PostMapping("/plans/{id}/simulations")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Simula a versão sobre um período passado e compara com os extratos efetivos")
    SimulationView simulate(@PathVariable UUID id, @RequestParam YearMonth period) {
        return plans.simulate(id, period);
    }

    @GetMapping("/goals")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    List<GoalView> goals(@RequestParam YearMonth period) {
        return plans.goals(period);
    }

    @PutMapping("/goals")
    @PreAuthorize("hasAuthority('COMMISSION_PLAN_MANAGE')")
    @Operation(summary = "Define a meta de um negociador ou equipe no período")
    GoalView setGoal(@Valid @RequestBody GoalRequest request) {
        return plans.setGoal(new GoalCommand(request.period(), request.subjectType(), request.subjectId(), request.target()));
    }

    record PlanRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                       @NotBlank @Size(max = 120) String name,
                       @Size(max = 1000) String description,
                       @Min(0) @Max(1000) int priority,
                       @NotNull PlanRules rules) {

        PlanCommand toCommand() {
            return new PlanCommand(code, name, description, priority, rules);
        }
    }

    record PublishRequest(@NotNull LocalDate effectiveFrom) {
    }

    record RetireRequest(@NotNull LocalDate effectiveTo) {
    }

    record GoalRequest(@NotNull YearMonth period, @NotBlank @Pattern(regexp = "USER|TEAM") String subjectType,
                       @NotNull UUID subjectId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal target) {
    }
}
