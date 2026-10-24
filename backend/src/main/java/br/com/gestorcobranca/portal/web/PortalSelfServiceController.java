package br.com.gestorcobranca.portal.web;

import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SessionGrant;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.AcceptCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.AcceptanceResult;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.AgreementDetail;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.AgreementSummary;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.DebtsView;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.OffersView;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.PixView;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.ServiceRequestCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.ServiceRequestView;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.SimulateCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.TermFile;
import br.com.gestorcobranca.portal.application.port.in.PortalSessionUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalSessionUseCases.SessionView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API do devedor autenticado no portal ({@code /api/portal/v1/me}, token do portal, ator
 * {@code DEBTOR}). Nenhum endpoint recebe o id do devedor: é sempre o da sessão.
 */
@RestController
@RequestMapping(ApiPaths.PORTAL + "/me")
@PreAuthorize("hasAuthority('ACTOR_DEBTOR')")
class PortalSelfServiceController {

    private final PortalSessionUseCases session;
    private final PortalSelfServiceUseCases selfService;

    PortalSelfServiceController(PortalSessionUseCases session, PortalSelfServiceUseCases selfService) {
        this.session = session;
        this.selfService = selfService;
    }

    record PrivacyRequest(@Min(1) int noticeVersion) {
    }

    record SimulateRequest(@Min(1) @Max(12) int installments, @DecimalMin("0.01") BigDecimal downPayment,
                           LocalDate firstDueDate) {
    }

    record AcceptRequest(@NotNull UUID simulationId, @Min(0) @Max(99) int level, @Min(1) @Max(12) int installments,
                         @DecimalMin("0.01") BigDecimal downPayment, LocalDate firstDueDate, boolean termsAccepted) {
    }

    record ServiceRequestBody(@NotBlank @Size(max = 20) String reason, @NotBlank @Size(max = 20) String preferredChannel,
                              UUID portfolioId, @Size(max = 500) String note) {
    }

    @GetMapping("/session")
    ResponseEntity<SessionView> current() {
        return noStore(session.current());
    }

    @PostMapping("/session/refresh")
    ResponseEntity<SessionGrant> refresh() {
        return noStore(session.refresh());
    }

    @PostMapping("/session/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout() {
        session.logout();
    }

    @PostMapping("/session/privacy")
    ResponseEntity<SessionView> acknowledgePrivacy(@Valid @RequestBody PrivacyRequest body) {
        return noStore(session.acknowledgePrivacy(body.noticeVersion()));
    }

    @GetMapping("/debts")
    ResponseEntity<DebtsView> debts() {
        return noStore(selfService.debts());
    }

    @GetMapping("/portfolios/{portfolioId}/offers")
    ResponseEntity<OffersView> offers(@PathVariable UUID portfolioId) {
        return noStore(selfService.offers(portfolioId));
    }

    @PostMapping("/portfolios/{portfolioId}/simulations")
    ResponseEntity<OffersView> simulate(@PathVariable UUID portfolioId, @Valid @RequestBody SimulateRequest body) {
        return noStore(selfService.simulate(portfolioId, new SimulateCommand(body.installments(), body.downPayment(),
                body.firstDueDate())));
    }

    @PostMapping("/agreements")
    ResponseEntity<AcceptanceResult> accept(@Valid @RequestBody AcceptRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(selfService.accept(new AcceptCommand(body.simulationId(), body.level(), body.installments(),
                        body.downPayment(), body.firstDueDate(), body.termsAccepted())));
    }

    @GetMapping("/agreements")
    ResponseEntity<List<AgreementSummary>> agreements() {
        return noStore(selfService.agreements());
    }

    @GetMapping("/agreements/{agreementId}")
    ResponseEntity<AgreementDetail> agreement(@PathVariable UUID agreementId) {
        return noStore(selfService.agreement(agreementId));
    }

    @PostMapping("/agreements/{agreementId}/installments/{installmentId}/pix")
    ResponseEntity<PixView> pix(@PathVariable UUID agreementId, @PathVariable UUID installmentId) {
        return noStore(selfService.pix(agreementId, installmentId));
    }

    @GetMapping("/agreements/{agreementId}/term")
    ResponseEntity<InputStreamResource> term(@PathVariable UUID agreementId) {
        TermFile term = selfService.term(agreementId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(term.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-SHA256", term.sha256());
        if (term.size() >= 0) {
            response.contentLength(term.size());
        }
        return response.body(new InputStreamResource(term.content()));
    }

    @PostMapping("/service-requests")
    ResponseEntity<ServiceRequestView> requestService(@Valid @RequestBody ServiceRequestBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(selfService.requestService(new ServiceRequestCommand(body.reason(), body.preferredChannel(),
                        body.portfolioId(), body.note())));
    }

    @GetMapping("/service-requests")
    ResponseEntity<List<ServiceRequestView>> serviceRequests() {
        return noStore(selfService.serviceRequests());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
