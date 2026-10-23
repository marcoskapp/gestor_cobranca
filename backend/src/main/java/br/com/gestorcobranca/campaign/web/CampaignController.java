package br.com.gestorcobranca.campaign.web;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.CampaignExecutionResult;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.CampaignPanel;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentFilter;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CadenceView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignFilter;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/** Campanhas, réguas versionadas, execução e painel (F13). */
@RestController
@RequestMapping(ApiPaths.V1 + "/campaign")
class CampaignController {

    static final String READ = "hasAuthority('CAMPAIGN_VIEW') or hasAuthority('CAMPAIGN_MANAGE') "
            + "or hasAuthority('CAMPAIGN_EXECUTE')";

    private final CampaignUseCases campaigns;
    private final CampaignExecutionUseCases execution;

    CampaignController(CampaignUseCases campaigns, CampaignExecutionUseCases execution) {
        this.campaigns = campaigns;
        this.execution = execution;
    }

    @GetMapping("/campaigns")
    @PreAuthorize(READ)
    PageResponse<CampaignView> search(@RequestParam(required = false) UUID creditorId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return campaigns.search(new CampaignFilter(creditorId, status), PageQuery.of(page, size));
    }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize(READ)
    CampaignView get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAMPAIGN_MANAGE')")
    CampaignView create(@Valid @RequestBody CreateCampaignRequest request) {
        return campaigns.create(new CreateCampaignCommand(request.code(), request.creditorId(), request.portfolioId(),
                request.settings().toSettings()));
    }

    @PutMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGN_MANAGE')")
    CampaignView update(@PathVariable UUID id, @Valid @RequestBody SettingsRequest request) {
        return campaigns.update(id, request.toSettings());
    }

    @GetMapping("/campaigns/{id}/cadences")
    @PreAuthorize(READ)
    List<CadenceView> cadences(@PathVariable UUID id) {
        return campaigns.cadences(id);
    }

    @GetMapping("/cadences/{cadenceId}")
    @PreAuthorize(READ)
    CadenceView cadence(@PathVariable UUID cadenceId) {
        return campaigns.cadence(cadenceId);
    }

    @PutMapping("/campaigns/{id}/cadences/draft")
    @PreAuthorize("hasAuthority('CAMPAIGN_MANAGE')")
    CadenceView saveDraft(@PathVariable UUID id, @NotNull @RequestBody CadenceDefinition definition) {
        return campaigns.saveDraft(id, definition);
    }

    @PostMapping("/campaigns/{id}/cadences/draft/publish")
    @PreAuthorize("hasAuthority('CAMPAIGN_MANAGE')")
    CadenceView publish(@PathVariable UUID id) {
        return campaigns.publishDraft(id);
    }

    @PostMapping("/campaigns/{id}/activate")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    CampaignExecutionResult activate(@PathVariable UUID id) {
        return execution.activate(id);
    }

    @PostMapping("/campaigns/{id}/pause")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    CampaignExecutionResult pause(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return execution.pause(id, request.note());
    }

    @PostMapping("/campaigns/{id}/finish")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    CampaignExecutionResult finish(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return execution.finish(id, request.note());
    }

    @PostMapping("/campaigns/{id}/reprocess")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    CampaignExecutionResult reprocess(@PathVariable UUID id) {
        return execution.reprocess(id);
    }

    @GetMapping("/campaigns/{id}/enrollments")
    @PreAuthorize(READ)
    PageResponse<EnrollmentView> enrollments(@PathVariable UUID id, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) UUID debtorId,
                                             @RequestParam(required = false) UUID caseId,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return execution.enrollments(id, new EnrollmentFilter(status, debtorId, caseId), PageQuery.of(page, size));
    }

    @GetMapping("/campaigns/{id}/dashboard")
    @PreAuthorize(READ)
    CampaignPanel dashboard(@PathVariable UUID id) {
        return execution.panel(id);
    }

    record CreateCampaignRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,59}") String code,
                                 @NotNull UUID creditorId, UUID portfolioId, @NotNull @Valid SettingsRequest settings) {
    }

    record SettingsRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                           @NotNull UUID segmentId, String purpose, Integer priority,
                           @Size(max = 60) String exclusivityGroup, Integer reenrollAfterDays,
                           Integer attributionWindowDays, Integer paymentPauseDays, Integer maxDeferralHours,
                           Boolean exitWhenOutOfSegment) {

        CampaignSettings toSettings() {
            return new CampaignSettings(name, description, segmentId, purpose, priority, exclusivityGroup,
                    reenrollAfterDays, attributionWindowDays, paymentPauseDays, maxDeferralHours, exitWhenOutOfSegment);
        }
    }

    record NoteRequest(@NotBlank @Size(max = 300) String note) {
    }
}
