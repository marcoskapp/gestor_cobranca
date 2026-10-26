package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.ApprovalCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.Preview;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateContent;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateFilter;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Templates versionados; operadores consultam para escolher o template do envio. */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/templates")
class TemplateController {

    private final TemplateUseCases templates;

    TemplateController(TemplateUseCases templates) {
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE') or hasAuthority('MESSAGE_SEND')")
    PageResponse<TemplateView> search(@RequestParam(required = false) UUID creditorId,
                                      @RequestParam(required = false) String code,
                                      @RequestParam(required = false) String channel,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return templates.search(new TemplateFilter(creditorId, code, channel, status), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE') or hasAuthority('MESSAGE_SEND')")
    TemplateView get(@PathVariable UUID id) {
        return templates.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE')")
    TemplateView create(@Valid @RequestBody CreateTemplateRequest request) {
        return templates.createDraft(new CreateTemplateCommand(request.creditorId(), request.code(), request.channel(),
                request.content().toContent()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE')")
    TemplateView update(@PathVariable UUID id, @Valid @RequestBody ContentRequest request) {
        return templates.updateDraft(id, request.toContent());
    }

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE')")
    TemplateView approval(@PathVariable UUID id, @Valid @RequestBody ApprovalRequest request) {
        return templates.registerApproval(id, new ApprovalCommand(request.decision(), request.providerTemplateName(),
                request.reference(), request.note(), request.decidedAt()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE')")
    TemplateView publish(@PathVariable UUID id) {
        return templates.publish(id);
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE')")
    TemplateView retire(@PathVariable UUID id) {
        return templates.retire(id);
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasAuthority('TEMPLATE_MANAGE') or hasAuthority('MESSAGE_SEND')")
    Preview preview(@PathVariable UUID id, @RequestBody(required = false) @Size(max = 20) Map<String, String> values) {
        return templates.preview(id, values == null ? Map.of() : values);
    }

    record CreateTemplateRequest(UUID creditorId, @NotBlank @Size(max = 60) String code,
                                 @NotBlank @Size(max = 20) String channel, @NotNull @Valid ContentRequest content) {
    }

    record ContentRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 30) String purpose,
                          @Size(max = 300) String subject, @NotBlank @Size(max = 8000) String body,
                          @Size(max = 20) List<@Valid VariableRequest> variables, @Size(max = 10) String language,
                          @Size(max = 120) String providerTemplateName, @Size(max = 20) String providerCategory) {

        TemplateContent toContent() {
            List<VariableInput> vars = variables == null ? List.of() : variables.stream()
                    .map(v -> new VariableInput(v.name(), v.type(), v.required(), v.maxLength())).toList();
            return new TemplateContent(name, purpose, subject, body, vars, language, providerTemplateName,
                    providerCategory);
        }
    }

    record VariableRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 20) String type,
                           boolean required, Integer maxLength) {
    }

    record ApprovalRequest(@NotBlank @Size(max = 20) String decision, @Size(max = 120) String providerTemplateName,
                           @Size(max = 120) String reference, @Size(max = 500) String note, Instant decidedAt) {
    }
}
