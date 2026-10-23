package br.com.gestorcobranca.campaign.web;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentDetail;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Inscrições: detalhe com as execuções de etapa, por caso, e comandos individuais. */
@RestController
@RequestMapping(ApiPaths.V1 + "/campaign/enrollments")
class EnrollmentController {

    private final CampaignExecutionUseCases execution;

    EnrollmentController(CampaignExecutionUseCases execution) {
        this.execution = execution;
    }

    @GetMapping
    @PreAuthorize(CampaignController.READ + " or hasAuthority('CASE_VIEW')")
    List<EnrollmentView> byCase(@RequestParam UUID caseId) {
        return execution.byCase(caseId);
    }

    @GetMapping("/{id}")
    @PreAuthorize(CampaignController.READ)
    EnrollmentDetail get(@PathVariable UUID id) {
        return execution.enrollment(id);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    EnrollmentView pause(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return execution.pauseEnrollment(id, request.note());
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    EnrollmentView resume(@PathVariable UUID id) {
        return execution.resumeEnrollment(id);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('CAMPAIGN_EXECUTE')")
    EnrollmentView stop(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return execution.stopEnrollment(id, request.note());
    }

    record NoteRequest(@NotBlank @Size(max = 300) String note) {
    }
}
