package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidationView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Simulação (composição, envelope e ofertas até a alçada do ator) e validação de condições pelo
 * {@code NegotiationGuard}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation")
@Tag(name = "Negotiation — simulação", description = "Cálculo da dívida, ofertas e validação de condições")
class SimulationController {

    private final NegotiationSimulationUseCases simulations;

    SimulationController(NegotiationSimulationUseCases simulations) {
        this.simulations = simulations;
    }

    @PostMapping("/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NEGOTIATION_SIMULATE')")
    @Operation(summary = "Simula a negociação dos títulos do devedor no credor (registrada com memória de cálculo)")
    SimulationView simulate(@Valid @RequestBody SimulateRequest request) {
        return simulations.simulate(new SimulateCommand(request.creditorId(), request.debtorId(), request.titleIds(),
                request.referenceDate(), request.requestedLevel(), justification(request.justification()),
                request.plans() == null ? List.of() : request.plans().stream().map(PlanRequest::toInput).toList(),
                request.channel()));
    }

    @GetMapping("/simulations/{id}")
    @PreAuthorize("hasAuthority('NEGOTIATION_SIMULATE')")
    SimulationView get(@PathVariable UUID id) {
        return simulations.get(id);
    }

    @PostMapping("/validations")
    @PreAuthorize("hasAuthority('NEGOTIATION_SIMULATE')")
    @Operation(summary = "Valida condições específicas contra política, envelope, alçada e elegibilidade (não grava)")
    ValidationView validate(@Valid @RequestBody ValidateRequest request) {
        return simulations.validate(new ValidateCommand(request.creditorId(), request.debtorId(), request.titleIds(),
                request.referenceDate(), request.policyId(), request.level(), justification(request.justification()),
                request.discounts(), request.plan().toInput(), request.channel()));
    }

    private static JustificationInput justification(JustificationRequest r) {
        return r == null ? null : new JustificationInput(r.reason(), r.note());
    }

    record SimulateRequest(@NotNull UUID creditorId, @NotNull UUID debtorId, @Size(max = 200) List<UUID> titleIds,
                           LocalDate referenceDate, @Min(0) @Max(99) Integer requestedLevel,
                           @Valid JustificationRequest justification, @Size(max = 12) List<@Valid PlanRequest> plans,
                           @Size(max = 20) String channel) {
    }

    record ValidateRequest(@NotNull UUID creditorId, @NotNull UUID debtorId, @Size(max = 200) List<UUID> titleIds,
                           LocalDate referenceDate, UUID policyId, @Min(0) @Max(99) int level,
                           @Valid JustificationRequest justification, ComponentsView discounts,
                           @NotNull @Valid PlanRequest plan, @Size(max = 20) String channel) {
    }

    record JustificationRequest(@NotBlank @Size(max = 30) String reason, @Size(max = 500) String note) {
    }

    record PlanRequest(@Min(1) @Max(360) int installments,
                       @DecimalMin(value = "0", inclusive = false) BigDecimal downPayment,
                       LocalDate firstDueDate) {

        PlanInput toInput() {
            return new PlanInput(installments, downPayment, firstDueDate);
        }
    }
}
