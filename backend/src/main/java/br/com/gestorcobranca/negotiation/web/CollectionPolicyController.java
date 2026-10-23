package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.EffectivePolicyView;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.PolicyView;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.UpdatePolicyCommand;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Políticas de cobrança e negociação versionadas por credor ({@code DRAFT → PUBLISHED → RETIRED}).
 * Termos tipados ({@link PolicyTerms}) validados integralmente no domínio (422 com código
 * {@code policy.terms.*}).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation/policies")
@Tag(name = "Negotiation — políticas", description = "Políticas de cobrança versionadas, sobreposições e vigência")
class CollectionPolicyController {

    private final CollectionPolicyUseCases policies;

    CollectionPolicyController(CollectionPolicyUseCases policies) {
        this.policies = policies;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    PageResponse<PolicyView> search(@RequestParam(required = false) UUID creditorId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return policies.search(creditorId, status, PageQuery.of(page, size));
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    @Operation(summary = "Modelo de termos com parâmetros usuais de mercado")
    PolicyTerms template() {
        return policies.template();
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    @Operation(summary = "Versão vigente do credor e regras efetivas para a carteira/faixa de atraso")
    EffectivePolicyView effective(@RequestParam UUID creditorId, @RequestParam(required = false) UUID portfolioId,
                                  @RequestParam(required = false) @Min(0) Long daysOverdue,
                                  @RequestParam(required = false) LocalDate date) {
        return policies.effective(creditorId, portfolioId, daysOverdue, date);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_VIEW')")
    PolicyView get(@PathVariable UUID id) {
        return policies.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Cria a política do credor (versão 1, rascunho)")
    PolicyView create(@Valid @RequestBody CreateRequest request) {
        return policies.createDraft(new CreatePolicyCommand(request.creditorId(), request.name(), request.description(),
                request.terms()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Altera um rascunho (versões publicadas são imutáveis)")
    PolicyView update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return policies.updateDraft(id, new UpdatePolicyCommand(request.name(), request.description(), request.terms()));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Cria nova versão em rascunho a partir desta")
    PolicyView newVersion(@PathVariable UUID id) {
        return policies.newVersion(id);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Publica a versão a partir de hoje ou de data futura (encerra a vigência da anterior)")
    PolicyView publish(@PathVariable UUID id, @Valid @RequestBody PublishRequest request) {
        return policies.publish(id, request.effectiveFrom());
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Encerra a vigência (ontem = retirada imediata)")
    PolicyView retire(@PathVariable UUID id, @Valid @RequestBody RetireRequest request) {
        return policies.retire(id, request.effectiveTo());
    }

    record CreateRequest(@NotNull UUID creditorId, @NotBlank @Size(max = 120) String name,
                         @Size(max = 1000) String description, @NotNull PolicyTerms terms) {
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                         @NotNull PolicyTerms terms) {
    }

    record PublishRequest(@NotNull LocalDate effectiveFrom) {
    }

    record RetireRequest(@NotNull LocalDate effectiveTo) {
    }
}
