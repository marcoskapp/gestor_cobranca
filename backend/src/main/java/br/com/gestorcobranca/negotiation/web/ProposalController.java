package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalFilter;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
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
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Propostas, fila de aprovação (F16), aceite com termo e formalização. */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation/proposals")
@Tag(name = "Negotiation — propostas", description = "Propostas, aprovação por alçada, aceite e formalização")
class ProposalController {

    private final ProposalUseCases proposals;

    ProposalController(ProposalUseCases proposals) {
        this.proposals = proposals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NEGOTIATION_PROPOSE')")
    @Operation(summary = "Cria a proposta a partir de uma simulação (dentro da alçada, para aprovação ou transferência)")
    ProposalView create(@Valid @RequestBody CreateProposalRequest r) {
        return proposals.create(new CreateProposalCommand(r.simulationId(), r.level(), r.discounts(), r.plan().toInput(),
                r.replacedAgreementId(), r.handoffProposalId(), Boolean.TRUE.equals(r.copilot()), r.channel()));
    }

    @PostMapping("/{id}/adopt")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NEGOTIATION_PROPOSE')")
    @Operation(summary = "Assume a sugestão do assistente transferida para humano, com as mesmas condições")
    ProposalView adopt(@PathVariable UUID id, @RequestParam(required = false) String channel) {
        return proposals.adopt(id, channel);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW') or hasAuthority('NEGOTIATION_PROPOSE') or hasAuthority('NEGOTIATION_APPROVE')")
    PageResponse<ProposalView> search(@RequestParam(required = false) UUID creditorId,
                                      @RequestParam(required = false) UUID debtorId,
                                      @RequestParam(required = false) UUID caseId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String actorType,
                                      @RequestParam(required = false) Boolean mine,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return proposals.search(new ProposalFilter(creditorId, debtorId, caseId, status, actorType,
                Boolean.TRUE.equals(mine)), PageQuery.of(page, size));
    }

    @GetMapping("/approval-queue")
    @PreAuthorize("hasAuthority('NEGOTIATION_APPROVE')")
    @Operation(summary = "Propostas aguardando aprovação que cabem na alçada do usuário")
    PageResponse<ProposalView> approvalQueue(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return proposals.approvalQueue(PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW') or hasAuthority('NEGOTIATION_PROPOSE') or hasAuthority('NEGOTIATION_APPROVE')")
    ProposalView get(@PathVariable UUID id) {
        return proposals.get(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('NEGOTIATION_APPROVE')")
    ProposalView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return proposals.approve(id, r == null ? null : r.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('NEGOTIATION_APPROVE')")
    ProposalView reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest r) {
        return proposals.reject(id, r.reason());
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('AGREEMENT_FORMALIZE')")
    @Operation(summary = "Registra o aceite do devedor (canal, instante, origem) e gera o termo em PDF com hash")
    ProposalView accept(@PathVariable UUID id, @Valid @RequestBody AcceptRequest r) {
        // IP e agente de usuário são os da própria requisição (contexto do servidor), nunca informados pelo cliente
        return proposals.accept(id, new AcceptCommand(r.channel(), r.reference(), null, null));
    }

    @PostMapping("/{id}/formalize")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('AGREEMENT_FORMALIZE')")
    @Operation(summary = "Formaliza o acordo da proposta aceita")
    AgreementView formalize(@PathVariable UUID id) {
        return proposals.formalize(id);
    }

    @GetMapping("/{id}/term")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW') or hasAuthority('NEGOTIATION_PROPOSE')")
    ResponseEntity<InputStreamResource> term(@PathVariable UUID id) {
        return TermResponses.of(proposals.openTerm(id));
    }

    record CreateProposalRequest(@NotNull UUID simulationId, @Min(0) @Max(99) int level, ComponentsView discounts,
                                 @NotNull @Valid PlanRequest plan, UUID replacedAgreementId, UUID handoffProposalId,
                                 Boolean copilot, @Size(max = 20) String channel) {
    }

    record PlanRequest(@Min(1) @Max(360) int installments,
                       @DecimalMin(value = "0", inclusive = false) BigDecimal downPayment, LocalDate firstDueDate) {

        PlanInput toInput() {
            return new PlanInput(installments, downPayment, firstDueDate);
        }
    }

    record DecisionRequest(@Size(max = 1000) String note) {
    }

    record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    record AcceptRequest(@NotBlank @Size(max = 20) String channel, @Size(max = 120) String reference) {
    }

}
