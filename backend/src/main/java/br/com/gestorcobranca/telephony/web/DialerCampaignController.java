package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignStats;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.EntryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Campanhas de discagem: configuração (gestor), operação e indicadores (supervisão). */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony/dialer-campaigns")
class DialerCampaignController {

    private final DialerCampaignUseCases campaigns;

    DialerCampaignController(DialerCampaignUseCases campaigns) {
        this.campaigns = campaigns;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    CampaignView create(@Valid @RequestBody CreateRequest request) {
        return campaigns.create(new CreateCampaignCommand(request.code(), request.creditorId(), request.queueId(),
                request.settings().toSettings()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    CampaignView update(@PathVariable UUID id, @Valid @RequestBody SettingsRequest request) {
        return campaigns.update(id, request.toSettings());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE') or hasAuthority('TELEPHONY_USE')")
    List<CampaignView> list() {
        return campaigns.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE') or hasAuthority('TELEPHONY_USE')")
    CampaignView get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    CampaignView activate(@PathVariable UUID id) {
        return campaigns.activate(id);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    CampaignView pause(@PathVariable UUID id) {
        return campaigns.pause(id);
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    CampaignView finish(@PathVariable UUID id) {
        return campaigns.finish(id);
    }

    @PostMapping("/{id}/reload")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    CampaignView reload(@PathVariable UUID id) {
        return campaigns.reload(id);
    }

    @GetMapping("/{id}/entries")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    PageResponse<EntryView> entries(@PathVariable UUID id, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return campaigns.entries(id, status, PageQuery.of(page, size));
    }

    @GetMapping("/{id}/stats")
    @PreAuthorize("hasAuthority('TELEPHONY_SUPERVISE')")
    CampaignStats stats(@PathVariable UUID id) {
        return campaigns.stats(id);
    }

    record CreateRequest(@NotBlank @Size(max = 40) String code, UUID creditorId, @NotNull UUID queueId,
                         @NotNull @Valid SettingsRequest settings) {
    }

    record SettingsRequest(@NotBlank @Size(max = 120) String name, UUID portfolioId, boolean includeAssigned,
                           @NotBlank @Size(max = 20) String mode,
                           @DecimalMin("1.00") @DecimalMax("5.00") BigDecimal maxDialRatio,
                           @DecimalMin("0.01") @DecimalMax("10.00") BigDecimal maxAbandonRate, Integer maxAttempts,
                           Integer retryIntervalMinutes, Integer ringTimeoutSeconds, LocalTime windowStart,
                           LocalTime windowEnd) {

        CampaignSettings toSettings() {
            return new CampaignSettings(name, portfolioId, includeAssigned, mode, maxDialRatio, maxAbandonRate,
                    maxAttempts, retryIntervalMinutes, ringTimeoutSeconds, windowStart, windowEnd);
        }
    }
}
