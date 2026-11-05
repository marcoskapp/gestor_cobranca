package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DeadlinePreview;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DeadlineRuleCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DeadlineRuleView;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DisputeSettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.DisputeSettingsView;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.PoolMemberView;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases.TemplateView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Configuração das contestações: parâmetros, prazos por tipo/credor, analistas da distribuição e modelos de e-mail. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/dispute-config")
class DisputeConfigController {

    private static final String READ = "hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK')";
    private static final String MANAGE = "hasAuthority('DISPUTE_MANAGE')";

    private final DisputeConfigUseCases config;

    DisputeConfigController(DisputeConfigUseCases config) {
        this.config = config;
    }

    @GetMapping("/settings")
    @PreAuthorize(READ)
    DisputeSettingsView settings() {
        return config.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(MANAGE)
    DisputeSettingsView updateSettings(@Valid @RequestBody SettingsRequest r) {
        return config.updateSettings(new DisputeSettingsCommand(r.distributionMode(), r.internalDays(),
                r.creditorRequestDays(), r.reminderDaysBefore(), r.evidenceLinkDays()));
    }

    @GetMapping("/deadline-rules")
    @PreAuthorize(READ)
    List<DeadlineRuleView> rules() {
        return config.rules();
    }

    @PostMapping("/deadline-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    DeadlineRuleView createRule(@Valid @RequestBody RuleRequest r) {
        return config.createRule(new DeadlineRuleCommand(r.creditorId(), r.type(), r.responseDays(), r.internalDays()));
    }

    @PutMapping("/deadline-rules/{id}")
    @PreAuthorize(MANAGE)
    DeadlineRuleView updateRule(@PathVariable UUID id, @Valid @RequestBody RuleDaysRequest r) {
        return config.updateRule(id, r.responseDays(), r.internalDays());
    }

    @DeleteMapping("/deadline-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    void removeRule(@PathVariable UUID id) {
        config.removeRule(id);
    }

    @GetMapping("/deadline-preview")
    @PreAuthorize(READ)
    DeadlinePreview preview(@RequestParam UUID creditorId, @RequestParam String type) {
        return config.previewDeadlines(creditorId, type);
    }

    @GetMapping("/pool")
    @PreAuthorize(MANAGE)
    List<PoolMemberView> pool() {
        return config.pool();
    }

    @PostMapping("/pool")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    PoolMemberView addPoolMember(@Valid @RequestBody PoolRequest r) {
        return config.addPoolMember(r.creditorId(), r.userId());
    }

    @DeleteMapping("/pool/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    void removePoolMember(@PathVariable UUID id) {
        config.removePoolMember(id);
    }

    @GetMapping("/templates")
    @PreAuthorize(READ)
    List<TemplateView> templates() {
        return config.templates();
    }

    @GetMapping("/templates/{kind}/{key}/history")
    @PreAuthorize(READ)
    List<TemplateView> history(@PathVariable String kind, @PathVariable String key) {
        return config.templateHistory(kind, key);
    }

    @PutMapping("/templates/{kind}/{key}")
    @PreAuthorize(MANAGE)
    TemplateView saveTemplate(@PathVariable String kind, @PathVariable String key, @Valid @RequestBody TemplateRequest r) {
        return config.saveTemplate(kind, key, r.subject(), r.body());
    }

    @PostMapping("/templates/{kind}/{key}/restore-default")
    @PreAuthorize(MANAGE)
    TemplateView restoreDefault(@PathVariable String kind, @PathVariable String key) {
        return config.restoreDefaultTemplate(kind, key);
    }

    record SettingsRequest(@NotBlank @Pattern(regexp = "MANUAL|LEAST_LOADED") String distributionMode,
                           @Min(1) @Max(90) int internalDays, @Min(1) @Max(60) int creditorRequestDays,
                           @Min(0) @Max(10) int reminderDaysBefore, @Min(1) @Max(30) int evidenceLinkDays) {
    }

    record RuleRequest(UUID creditorId, @Size(max = 30) String type, @Min(1) @Max(90) int responseDays,
                       @Min(1) @Max(90) int internalDays) {
    }

    record RuleDaysRequest(@Min(1) @Max(90) int responseDays, @Min(1) @Max(90) int internalDays) {
    }

    record PoolRequest(UUID creditorId, @NotNull UUID userId) {
    }

    record TemplateRequest(@NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 8000) String body) {
    }
}
