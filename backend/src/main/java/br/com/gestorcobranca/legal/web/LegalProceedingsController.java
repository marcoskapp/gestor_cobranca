package br.com.gestorcobranca.legal.web;

import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.MeasureView;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases.HearingView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementResult;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.SyncResult;
import br.com.gestorcobranca.legal.web.LegalRequests.HearingRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.HearingResultRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.LawsuitRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.MeasureRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.MeasureStatusRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.MovementRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.ReasonRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.StatusRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.SubmitRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.UpdateLawsuitRequest;
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
import java.util.UUID;

/** Medidas extrajudiciais, processos, andamentos e audiências. */
@RestController
@RequestMapping(ApiPaths.V1 + "/legal")
class LegalProceedingsController {

    private final ExtrajudicialMeasureUseCases measures;
    private final LawsuitUseCases lawsuits;
    private final HearingUseCases hearings;

    LegalProceedingsController(ExtrajudicialMeasureUseCases measures, LawsuitUseCases lawsuits, HearingUseCases hearings) {
        this.measures = measures;
        this.lawsuits = lawsuits;
        this.hearings = hearings;
    }

    // ---------------------------------------------------------------- medidas

    @GetMapping("/matters/{id}/measures")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<MeasureView> measures(@PathVariable UUID id) {
        return measures.list(id);
    }

    @PostMapping("/matters/{id}/measures")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MeasureView registerMeasure(@PathVariable UUID id, @Valid @RequestBody MeasureRequest request) {
        return measures.register(id, request.toCommand());
    }

    @GetMapping("/measures/{measureId}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    MeasureView measure(@PathVariable UUID measureId) {
        return measures.get(measureId);
    }

    @PostMapping("/measures/{measureId}/submission")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MeasureView submit(@PathVariable UUID measureId, @Valid @RequestBody SubmitRequest request) {
        return measures.submit(measureId, request.toCommand());
    }

    /** Envio automático pelo provedor (exige também DEBTOR_SENSITIVE_VIEW; com adaptador manual: 409). */
    @PostMapping("/measures/{measureId}/dispatch")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE') and hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    MeasureView dispatch(@PathVariable UUID measureId) {
        return measures.dispatch(measureId);
    }

    @PostMapping("/measures/{measureId}/status")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MeasureView changeMeasureStatus(@PathVariable UUID measureId, @Valid @RequestBody MeasureStatusRequest request) {
        return measures.changeStatus(measureId, request.toCommand());
    }

    // ---------------------------------------------------------------- processos e andamentos

    @GetMapping("/matters/{id}/lawsuits")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<LawsuitView> lawsuits(@PathVariable UUID id) {
        return lawsuits.list(id);
    }

    @PostMapping("/matters/{id}/lawsuits")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawsuitView registerLawsuit(@PathVariable UUID id, @Valid @RequestBody LawsuitRequest request) {
        return lawsuits.register(id, request.toCommand());
    }

    @GetMapping("/lawsuits/{lawsuitId}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    LawsuitView lawsuit(@PathVariable UUID lawsuitId) {
        return lawsuits.get(lawsuitId);
    }

    @PutMapping("/lawsuits/{lawsuitId}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawsuitView updateLawsuit(@PathVariable UUID lawsuitId, @Valid @RequestBody UpdateLawsuitRequest request) {
        return lawsuits.update(lawsuitId, request.toCommand());
    }

    @PostMapping("/lawsuits/{lawsuitId}/status")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawsuitView changeLawsuitStatus(@PathVariable UUID lawsuitId, @Valid @RequestBody StatusRequest request) {
        return lawsuits.changeStatus(lawsuitId, request.status(), request.reason());
    }

    @GetMapping("/lawsuits/{lawsuitId}/movements")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    PageResponse<MovementView> movements(@PathVariable UUID lawsuitId, @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return lawsuits.movements(lawsuitId, PageQuery.of(page, size));
    }

    @PostMapping("/lawsuits/{lawsuitId}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MovementResult recordMovement(@PathVariable UUID lawsuitId, @Valid @RequestBody MovementRequest request) {
        return lawsuits.recordMovement(lawsuitId, request.toCommand());
    }

    /** Captura de andamentos pelo provedor de dados processuais (com adaptador manual: 409). */
    @PostMapping("/lawsuits/{lawsuitId}/sync")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    SyncResult synchronize(@PathVariable UUID lawsuitId) {
        return lawsuits.synchronize(lawsuitId);
    }

    // ---------------------------------------------------------------- audiências

    @GetMapping("/matters/{id}/hearings")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<HearingView> matterHearings(@PathVariable UUID id) {
        return hearings.listByMatter(id);
    }

    @GetMapping("/lawsuits/{lawsuitId}/hearings")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<HearingView> hearings(@PathVariable UUID lawsuitId) {
        return hearings.list(lawsuitId);
    }

    @PostMapping("/lawsuits/{lawsuitId}/hearings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    HearingView schedule(@PathVariable UUID lawsuitId, @Valid @RequestBody HearingRequest request) {
        return hearings.schedule(lawsuitId, request.toCommand());
    }

    @PutMapping("/hearings/{hearingId}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    HearingView reschedule(@PathVariable UUID hearingId, @Valid @RequestBody HearingRequest request) {
        return hearings.reschedule(hearingId, request.toCommand());
    }

    @PostMapping("/hearings/{hearingId}/result")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    HearingView result(@PathVariable UUID hearingId, @Valid @RequestBody HearingResultRequest request) {
        return hearings.recordResult(hearingId, request.outcome(), request.notes());
    }

    @PostMapping("/hearings/{hearingId}/cancel")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    HearingView cancelHearing(@PathVariable UUID hearingId, @Valid @RequestBody ReasonRequest request) {
        return hearings.cancel(hearingId, request.reason());
    }
}
