package br.com.gestorcobranca.publicapi.web.admin;

import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.ScopeView;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.TokenView;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.UpdateTokenCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssueTokenCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestão dos tokens de integração pela assessoria (API interna, usuário autenticado). O segredo só aparece na resposta
 * da criação e da rotação, sempre com {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/public-api/tokens")
class IntegrationTokenController {

    private final IntegrationTokenUseCases tokens;
    private final IssueIntegrationToken issuer;

    IntegrationTokenController(IntegrationTokenUseCases tokens, IssueIntegrationToken issuer) {
        this.tokens = tokens;
        this.issuer = issuer;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_VIEW')")
    PageResponse<TokenView> search(@RequestParam(required = false) UUID creditorId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return tokens.search(creditorId, status, PageQuery.of(page, size));
    }

    @GetMapping("/scopes")
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_VIEW')")
    List<ScopeView> scopes() {
        return tokens.scopes();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_VIEW')")
    TokenView get(@PathVariable UUID id) {
        return tokens.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_MANAGE')")
    ResponseEntity<IssuedToken> create(@Valid @RequestBody CreateTokenRequest r) {
        IssuedToken issued = issuer.issue(new IssueTokenCommand(r.creditorId(), r.name(), r.description(), r.scopes(),
                r.rateLimitPerMinute(), r.allowedIps(), r.expiresAt()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(issued);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_MANAGE')")
    TokenView update(@PathVariable UUID id, @Valid @RequestBody UpdateTokenRequest r) {
        return tokens.updateSettings(id, new UpdateTokenCommand(r.name(), r.description(), r.rateLimitPerMinute(),
                r.allowedIps()));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_MANAGE')")
    TokenView revoke(@PathVariable UUID id, @Valid @RequestBody RevokeRequest r) {
        return tokens.revoke(id, r.reason());
    }

    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasAuthority('PUBLIC_API_TOKEN_MANAGE')")
    ResponseEntity<IssuedToken> rotate(@PathVariable UUID id, @Valid @RequestBody RotateRequest r) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.rotate(id, r.graceMinutes()));
    }

    record CreateTokenRequest(@NotNull UUID creditorId, @NotBlank @Size(max = 100) String name,
                              @Size(max = 500) String description, @NotEmpty @Size(max = 20) Set<@NotBlank String> scopes,
                              @Min(1) @Max(6000) Integer rateLimitPerMinute,
                              @Size(max = 20) List<@Size(max = 49) String> allowedIps, Instant expiresAt) {
    }

    record UpdateTokenRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
                              @Min(1) @Max(6000) int rateLimitPerMinute,
                              @Size(max = 20) List<@Size(max = 49) String> allowedIps) {
    }

    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record RotateRequest(@Min(0) @Max(1440) int graceMinutes) {
    }
}
