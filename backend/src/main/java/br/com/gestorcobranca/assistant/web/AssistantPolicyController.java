package br.com.gestorcobranca.assistant.web;

import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.PolicyView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.PromptView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.UpdatePolicyCommand;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.UUID;

/** Políticas do assistente (versões, publicação, desligamento por carteira) e prompts versionados. */
@RestController
@RequestMapping(ApiPaths.V1 + "/assistant")
@Tag(name = "Assistant — políticas", description = "Política do assistente de IA: modo, canais, alçada, gatilhos e limites")
class AssistantPolicyController {

    private static final String MANAGE = "hasAuthority('ASSISTANT_MANAGE')";
    private static final String VIEW = "hasAnyAuthority('ASSISTANT_MANAGE', 'ASSISTANT_REVIEW')";

    private final AssistantPolicyUseCases policies;

    AssistantPolicyController(AssistantPolicyUseCases policies) {
        this.policies = policies;
    }

    @GetMapping("/policies")
    @PreAuthorize(VIEW)
    List<PolicyView> list() {
        return policies.list();
    }

    @GetMapping("/policies/template")
    @PreAuthorize(MANAGE)
    PolicySettings template(@RequestParam(required = false) String mode) {
        return policies.template(mode);
    }

    @GetMapping("/policies/effective")
    @PreAuthorize(VIEW)
    ResponseEntity<PolicyView> effective(@RequestParam UUID creditorId, @RequestParam(required = false) UUID portfolioId) {
        return policies.effective(creditorId, portfolioId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/policies/{id}")
    @PreAuthorize(VIEW)
    PolicyView get(@PathVariable UUID id) {
        return policies.get(id);
    }

    @GetMapping("/policies/{id}/versions")
    @PreAuthorize(VIEW)
    List<PolicyView> versions(@PathVariable UUID id) {
        return policies.versions(policies.get(id).code());
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    PolicyView create(@Valid @RequestBody CreateRequest request) {
        return policies.createDraft(new CreatePolicyCommand(request.creditorId(), request.portfolioId(), request.code(),
                request.name(), request.settings()));
    }

    @PutMapping("/policies/{id}")
    @PreAuthorize(MANAGE)
    PolicyView update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return policies.updateDraft(id, new UpdatePolicyCommand(request.name(), request.settings()));
    }

    @PostMapping("/policies/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    PolicyView newVersion(@PathVariable UUID id) {
        return policies.newVersion(id);
    }

    @PostMapping("/policies/{id}/publish")
    @PreAuthorize(MANAGE)
    PolicyView publish(@PathVariable UUID id) {
        return policies.publish(id);
    }

    @PostMapping("/policies/{id}/retire")
    @PreAuthorize(MANAGE)
    PolicyView retire(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return policies.retire(id, request.reason());
    }

    @PostMapping("/portfolios/{portfolioId}/disable")
    @PreAuthorize(MANAGE)
    PolicyView disablePortfolio(@PathVariable UUID portfolioId, @Valid @RequestBody ReasonRequest request) {
        return policies.disablePortfolio(portfolioId, request.reason());
    }

    @GetMapping("/prompts")
    @PreAuthorize(VIEW)
    List<PromptView> prompts() {
        return policies.prompts();
    }

    record CreateRequest(UUID creditorId, UUID portfolioId,
                         @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}") String code,
                         @NotBlank @Size(max = 120) String name, @NotNull PolicySettings settings) {
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String name, @NotNull PolicySettings settings) {
    }

    record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
