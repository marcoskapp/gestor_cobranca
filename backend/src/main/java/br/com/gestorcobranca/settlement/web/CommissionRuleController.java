package br.com.gestorcobranca.settlement.web;

import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.CalculationView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetCommand;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.SimulateCommand;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Regras de comissionamento da assessoria por contrato de serviço (versões, publicação com vigência e
 * simulação). As regras são tipadas ({@link CommissionRules}) e validadas integralmente no domínio.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/settlement/rule-sets")
class CommissionRuleController {

    private final CommissionRuleUseCases rules;

    CommissionRuleController(CommissionRuleUseCases rules) {
        this.rules = rules;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('COMMISSION_VIEW', 'COMMISSION_MANAGE')")
    List<RuleSetView> list(@RequestParam(required = false) UUID creditorId,
                           @RequestParam(required = false) UUID contractId) {
        return rules.list(creditorId, contractId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('COMMISSION_VIEW', 'COMMISSION_MANAGE')")
    RuleSetView get(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    RuleSetView create(@Valid @RequestBody RuleSetRequest request) {
        return rules.createDraft(new RuleSetCommand(request.contractId(), request.rules(), request.notes()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    RuleSetView update(@PathVariable UUID id, @Valid @RequestBody RuleSetRequest request) {
        return rules.updateDraft(id, new RuleSetCommand(request.contractId(), request.rules(), request.notes()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    RuleSetView publish(@PathVariable UUID id, @Valid @RequestBody PublishRequest request) {
        return rules.publish(id, request.effectiveFrom());
    }

    @PostMapping("/{id}/discard")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    RuleSetView discard(@PathVariable UUID id) {
        return rules.discard(id);
    }

    @PostMapping("/simulate")
    @PreAuthorize("hasAnyAuthority('COMMISSION_VIEW', 'COMMISSION_MANAGE')")
    CalculationView simulate(@Valid @RequestBody SimulateRequest r) {
        return rules.simulate(new SimulateCommand(r.ruleSetId(), r.rules(), r.amount(), r.honorarium(), r.portfolioId(),
                r.daysPastDue(), r.paymentType(), r.receivedBy(), r.renegotiation(), r.firstReceiptOfAgreement()));
    }

    record RuleSetRequest(UUID contractId, @NotNull CommissionRules rules, @Size(max = 2000) String notes) {
    }

    record PublishRequest(@NotNull LocalDate effectiveFrom) {
    }

    record SimulateRequest(UUID ruleSetId, CommissionRules rules,
                           @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                           @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal honorarium,
                           UUID portfolioId, @Min(0) @Max(36500) Integer daysPastDue,
                           @Size(max = 20) String paymentType, @Size(max = 20) String receivedBy, boolean renegotiation,
                           boolean firstReceiptOfAgreement) {
    }
}
