package br.com.gestorcobranca.publicapi.web.platform;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases.BulkResult;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases.PlatformTokenView;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Controle dos tokens de integração de uma assessoria pela equipe da plataforma (área da plataforma, tela de
 * faturamento da assessoria): consulta com {@code PLATFORM_BILLING_VIEW}; pausa, retomada e revogação — de um token ou
 * de todos — com {@code PLATFORM_API_TOKENS_MANAGE}. Motivo obrigatório em todas as ações.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/platform/billing/tenants/{tenantId}/tokens")
class PlatformTokenController {

    private final PlatformTokenControlUseCases control;

    PlatformTokenController(PlatformTokenControlUseCases control) {
        this.control = control;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    List<PlatformTokenView> tokens(@PathVariable UUID tenantId) {
        return control.tokens(tenantId);
    }

    @PostMapping("/{tokenId}/pause")
    @PreAuthorize("hasAuthority('PLATFORM_API_TOKENS_MANAGE')")
    PlatformTokenView pause(@PathVariable UUID tenantId, @PathVariable UUID tokenId, @Valid @RequestBody PauseRequest r) {
        return control.pause(tenantId, tokenId, r.reason(), r.resumeAt());
    }

    @PostMapping("/{tokenId}/resume")
    @PreAuthorize("hasAuthority('PLATFORM_API_TOKENS_MANAGE')")
    PlatformTokenView resume(@PathVariable UUID tenantId, @PathVariable UUID tokenId,
                             @Valid @RequestBody ReasonRequest r) {
        return control.resume(tenantId, tokenId, r.reason());
    }

    @PostMapping("/{tokenId}/revoke")
    @PreAuthorize("hasAuthority('PLATFORM_API_TOKENS_MANAGE')")
    PlatformTokenView revoke(@PathVariable UUID tenantId, @PathVariable UUID tokenId,
                             @Valid @RequestBody ReasonRequest r) {
        return control.revoke(tenantId, tokenId, r.reason());
    }

    @PostMapping("/pause-all")
    @PreAuthorize("hasAuthority('PLATFORM_API_TOKENS_MANAGE')")
    BulkResult pauseAll(@PathVariable UUID tenantId, @Valid @RequestBody PauseRequest r) {
        return control.pauseAll(tenantId, r.reason(), r.resumeAt());
    }

    @PostMapping("/resume-all")
    @PreAuthorize("hasAuthority('PLATFORM_API_TOKENS_MANAGE')")
    BulkResult resumeAll(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest r) {
        return control.resumeAll(tenantId, r.reason());
    }

    /** @param resumeAt retomada automática (opcional; no futuro, até 90 dias) */
    record PauseRequest(@NotBlank @Size(max = 500) String reason, Instant resumeAt) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
