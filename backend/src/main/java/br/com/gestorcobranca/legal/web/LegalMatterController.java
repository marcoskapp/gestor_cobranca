package br.com.gestorcobranca.legal.web;

import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.AuthorizationView;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.EligibilityPreview;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.AgreementLinkView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.Dashboard;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterSheet;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.StageEntryView;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases.SettingsView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ViabilityResultView;
import br.com.gestorcobranca.legal.web.LegalRequests.AssessmentRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.AssignRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.AuthorizationDecisionRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.AuthorizationRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.InputRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.ReferRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.RuleRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.SettingsRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.TransitionRequest;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Jurídico: parâmetros, regras de elegibilidade, casos jurídicos (indicação, fases, responsável,
 * ficha, linha do tempo, acordos vinculados, painel), viabilidade e autorização do credor.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/legal")
class LegalMatterController {

    private final LegalSettingsUseCases settings;
    private final LegalEligibilityUseCases eligibility;
    private final LegalMatterUseCases matters;
    private final LegalViabilityUseCases viability;
    private final CreditorAuthorizationUseCases authorizations;

    LegalMatterController(LegalSettingsUseCases settings, LegalEligibilityUseCases eligibility,
                          LegalMatterUseCases matters, LegalViabilityUseCases viability,
                          CreditorAuthorizationUseCases authorizations) {
        this.settings = settings;
        this.eligibility = eligibility;
        this.matters = matters;
        this.viability = viability;
        this.authorizations = authorizations;
    }

    // ---------------------------------------------------------------- parâmetros

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    SettingsView settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    SettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return settings.update(request.toCommand());
    }

    // ---------------------------------------------------------------- elegibilidade

    @GetMapping("/eligibility-rules")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<RuleView> rules() {
        return eligibility.listRules();
    }

    @GetMapping("/eligibility-rules/{id}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    RuleView rule(@PathVariable UUID id) {
        return eligibility.getRule(id);
    }

    @PostMapping("/eligibility-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    RuleView createRule(@Valid @RequestBody RuleRequest request) {
        return eligibility.createRule(request.toCommand());
    }

    @PutMapping("/eligibility-rules/{id}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    RuleView updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return eligibility.updateRule(id, request.toCommand());
    }

    @PostMapping("/eligibility-rules/{id}/activate")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    RuleView activateRule(@PathVariable UUID id) {
        return eligibility.setRuleActive(id, true);
    }

    @PostMapping("/eligibility-rules/{id}/deactivate")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    RuleView deactivateRule(@PathVariable UUID id) {
        return eligibility.setRuleActive(id, false);
    }

    @GetMapping("/eligibility/cases/{caseId}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    EligibilityPreview evaluate(@PathVariable UUID caseId) {
        return eligibility.evaluate(caseId);
    }

    // ---------------------------------------------------------------- casos jurídicos

    @PostMapping("/matters")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MatterView refer(@Valid @RequestBody ReferRequest request) {
        return matters.refer(request.toCommand());
    }

    @GetMapping("/matters")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    PageResponse<MatterView> search(@RequestParam(required = false) UUID creditorId,
                                    @RequestParam(required = false) UUID portfolioId,
                                    @RequestParam(required = false) UUID debtorId,
                                    @RequestParam(required = false) UUID caseId,
                                    @RequestParam(required = false) Set<String> stage,
                                    @RequestParam(required = false) String responsibleType,
                                    @RequestParam(required = false) UUID responsibleUserId,
                                    @RequestParam(required = false) UUID lawFirmId,
                                    @RequestParam(required = false) Boolean open,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return matters.search(new MatterFilter(creditorId, portfolioId, debtorId, caseId, stage, responsibleType,
                responsibleUserId, lawFirmId, open), PageQuery.of(page, size));
    }

    @GetMapping("/matters/{id}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    MatterView get(@PathVariable UUID id) {
        return matters.get(id);
    }

    @GetMapping("/matters/{id}/sheet")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    MatterSheet sheet(@PathVariable UUID id) {
        return matters.sheet(id);
    }

    @GetMapping("/matters/{id}/collaterals")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<LegalMatterUseCases.MatterCollateralView> collaterals(@PathVariable UUID id) {
        return matters.collaterals(id);
    }

    @GetMapping("/matters/{id}/history")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<StageEntryView> history(@PathVariable UUID id) {
        return matters.history(id);
    }

    @PostMapping("/matters/{id}/transitions")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MatterView transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return matters.transition(id, request.toCommand());
    }

    @PutMapping("/matters/{id}/responsible")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MatterView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return matters.assign(id, request.toCommand());
    }

    @GetMapping("/matters/{id}/agreements")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<AgreementLinkView> agreements(@PathVariable UUID id) {
        return matters.agreements(id);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    Dashboard dashboard(@RequestParam(required = false) UUID creditorId) {
        return matters.dashboard(creditorId);
    }

    // ---------------------------------------------------------------- viabilidade

    @PostMapping("/matters/{id}/viability/preview")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    ViabilityResultView previewViability(@PathVariable UUID id, @Valid @RequestBody InputRequest request) {
        return viability.preview(id, request.toInput());
    }

    @PostMapping("/matters/{id}/viability")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    AssessmentView recordViability(@PathVariable UUID id, @Valid @RequestBody AssessmentRequest request) {
        return viability.record(id, request.toCommand());
    }

    @GetMapping("/matters/{id}/viability")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<AssessmentView> viabilityHistory(@PathVariable UUID id) {
        return viability.history(id);
    }

    // ---------------------------------------------------------------- autorização do credor

    @GetMapping("/matters/{id}/authorizations")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<AuthorizationView> authorizations(@PathVariable UUID id) {
        return authorizations.list(id);
    }

    @PostMapping("/matters/{id}/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    AuthorizationView requestAuthorization(@PathVariable UUID id, @Valid @RequestBody AuthorizationRequest request) {
        return authorizations.request(id, request.toCommand());
    }

    @PostMapping("/authorizations/{authorizationId}/decision")
    @PreAuthorize("hasAuthority('LEGAL_AUTHORIZE')")
    AuthorizationView decide(@PathVariable UUID authorizationId,
                             @Valid @RequestBody AuthorizationDecisionRequest request) {
        return authorizations.decide(authorizationId, request.toCommand());
    }
}
