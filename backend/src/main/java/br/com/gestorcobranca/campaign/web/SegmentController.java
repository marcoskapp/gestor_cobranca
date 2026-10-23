package br.com.gestorcobranca.campaign.web;

import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.AudienceCount;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.CreateSegmentCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.PreviewCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.SegmentFilter;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.SegmentView;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.UpdateSegmentCommand;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
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

import java.util.UUID;

/** Segmentos salvos e contagem prévia (F13). */
@RestController
@RequestMapping(ApiPaths.V1 + "/campaign")
class SegmentController {

    private static final String READ = "hasAuthority('SEGMENT_MANAGE') or hasAuthority('CAMPAIGN_MANAGE') "
            + "or hasAuthority('CAMPAIGN_VIEW')";

    private final SegmentUseCases segments;

    SegmentController(SegmentUseCases segments) {
        this.segments = segments;
    }

    @GetMapping("/segments")
    @PreAuthorize(READ)
    PageResponse<SegmentView> search(@RequestParam(required = false) UUID creditorId,
                                     @RequestParam(required = false) Boolean active,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return segments.search(new SegmentFilter(creditorId, active), PageQuery.of(page, size));
    }

    @GetMapping("/segments/{id}")
    @PreAuthorize(READ)
    SegmentView get(@PathVariable UUID id) {
        return segments.get(id);
    }

    @PostMapping("/segments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE')")
    SegmentView create(@Valid @RequestBody CreateSegmentRequest request) {
        return segments.create(new CreateSegmentCommand(request.creditorId(), request.code(), request.name(),
                request.description(), request.criteria()));
    }

    @PutMapping("/segments/{id}")
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE')")
    SegmentView update(@PathVariable UUID id, @Valid @RequestBody UpdateSegmentRequest request) {
        return segments.update(id, new UpdateSegmentCommand(request.name(), request.description(), request.criteria()));
    }

    @PostMapping("/segments/{id}/activate")
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE')")
    SegmentView activate(@PathVariable UUID id) {
        return segments.changeActive(id, true);
    }

    @PostMapping("/segments/{id}/deactivate")
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE')")
    SegmentView deactivate(@PathVariable UUID id) {
        return segments.changeActive(id, false);
    }

    @GetMapping("/segments/{id}/count")
    @PreAuthorize(READ)
    AudienceCount count(@PathVariable UUID id, @RequestParam(required = false) UUID creditorId,
                        @RequestParam(required = false) UUID portfolioId) {
        return segments.count(id, creditorId, portfolioId);
    }

    @PostMapping("/segments/preview")
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE') or hasAuthority('CAMPAIGN_MANAGE')")
    AudienceCount preview(@Valid @RequestBody PreviewRequest request) {
        return segments.preview(new PreviewCommand(request.creditorId(), request.portfolioId(), request.criteria()));
    }

    @PostMapping("/audience/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SEGMENT_MANAGE') or hasAuthority('CAMPAIGN_MANAGE')")
    void rebuild(@RequestParam(required = false) UUID creditorId) {
        segments.rebuildAudience(creditorId);
    }

    record CreateSegmentRequest(UUID creditorId,
                                @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,59}") String code,
                                @NotBlank @Size(max = 120) String name,
                                @Size(max = 500) String description,
                                @NotNull SegmentCriteria criteria) {
    }

    record UpdateSegmentRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                                @NotNull SegmentCriteria criteria) {
    }

    record PreviewRequest(UUID creditorId, UUID portfolioId, @NotNull SegmentCriteria criteria) {
    }
}
