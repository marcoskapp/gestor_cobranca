package br.com.gestorcobranca.portal.web;

import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.LogoContent;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationView;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.FunnelView;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.LogoUpload;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.SessionSummary;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.AccessLinkView;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.SendLinkCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.ServiceRequestSummary;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gestão interna do portal ({@code /api/v1/portal}): configuração por credor, funil, sessões, links e pedidos. */
@RestController
@RequestMapping(ApiPaths.V1 + "/portal")
class PortalAdminController {

    private final PortalConfigurationUseCases configurations;
    private final PortalOperationsUseCases operations;

    PortalAdminController(PortalConfigurationUseCases configurations, PortalOperationsUseCases operations) {
        this.configurations = configurations;
        this.operations = operations;
    }

    record ConfigurationRequest(UUID creditorId,
                                @NotBlank @Size(max = 60) String slug,
                                boolean enabled,
                                @NotBlank @Size(max = 80) String displayName,
                                @NotBlank @Size(max = 7) String primaryColor,
                                @NotBlank @Size(max = 7) String accentColor,
                                @Size(max = 200) Set<UUID> portfolioIds,
                                @NotNull @Size(min = 1, max = 3) Set<String> otpChannels,
                                @Size(max = 20) String knowledgeChallenge,
                                @Min(1) @Max(168) int linkValidityHours,
                                @NotBlank @Size(max = 8000) String privacyNoticeText,
                                @Size(max = 300) String supportText) {

        ConfigurationCommand command() {
            return new ConfigurationCommand(creditorId, slug, enabled, displayName, primaryColor, accentColor, portfolioIds,
                    otpChannels, knowledgeChallenge, linkValidityHours, privacyNoticeText, supportText);
        }
    }

    record RevokeRequest(@Size(max = 300) String reason) {
    }

    record LinkRequest(@NotNull UUID debtorId, @NotNull UUID creditorId, @NotBlank @Size(max = 20) String channel) {
    }

    @GetMapping("/configurations")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    List<ConfigurationView> list() {
        return configurations.list();
    }

    @PostMapping("/configurations")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    ConfigurationView create(@Valid @RequestBody ConfigurationRequest body) {
        return configurations.create(body.command());
    }

    @GetMapping("/configurations/{id}")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    ConfigurationView get(@PathVariable UUID id) {
        return configurations.get(id);
    }

    @PutMapping("/configurations/{id}")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    ConfigurationView update(@PathVariable UUID id, @Valid @RequestBody ConfigurationRequest body) {
        return configurations.update(id, body.command());
    }

    @PostMapping(path = "/configurations/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    ConfigurationView uploadLogo(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        try {
            return configurations.uploadLogo(id, new LogoUpload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DeleteMapping("/configurations/{id}/logo")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    ConfigurationView removeLogo(@PathVariable UUID id) {
        return configurations.removeLogo(id);
    }

    @GetMapping("/configurations/{id}/logo")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    ResponseEntity<InputStreamResource> logo(@PathVariable UUID id) {
        LogoContent logo = configurations.logo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.noCache()).header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(logo.content()));
    }

    @GetMapping("/configurations/{id}/funnel")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    FunnelView funnel(@PathVariable UUID id,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return configurations.funnel(id, from, to);
    }

    @GetMapping("/configurations/{id}/sessions")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    PageResponse<SessionSummary> sessions(@PathVariable UUID id, @RequestParam(required = false) UUID debtorId,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return configurations.sessions(id, debtorId, PageQuery.of(page, size));
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    @PreAuthorize("hasAuthority('PORTAL_MANAGE')")
    SessionSummary revoke(@PathVariable UUID sessionId, @Valid @RequestBody(required = false) RevokeRequest body) {
        return configurations.revokeSession(sessionId, body == null ? null : body.reason());
    }

    @PostMapping("/access-links")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    @ResponseStatus(HttpStatus.CREATED)
    AccessLinkView sendLink(@Valid @RequestBody LinkRequest body) {
        return operations.sendAccessLink(new SendLinkCommand(body.debtorId(), body.creditorId(), body.channel()));
    }

    @GetMapping("/service-requests")
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    PageResponse<ServiceRequestSummary> serviceRequests(@RequestParam(required = false) UUID creditorId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return operations.serviceRequests(creditorId, status, PageQuery.of(page, size));
    }
}
