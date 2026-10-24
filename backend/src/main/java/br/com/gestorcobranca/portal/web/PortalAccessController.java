package br.com.gestorcobranca.portal.web;

import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.AccessLockedException;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.ChallengeView;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.ClientInfo;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.LinkResult;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.LogoContent;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.RequestCodeCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SessionGrant;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SiteView;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.VerifyCommand;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Acesso público ao portal de um credor ({@code PublicEndpoint}): marca, logotipo, pedido de código,
 * verificação e resgate do link. Limite por IP antes de tudo (429 + {@code RATE_LIMITED}); bloqueio
 * progressivo → 429 com {@code Retry-After}; falha de verificação → 401 {@code portal.access_invalid}.
 */
@RestController
@RequestMapping(ApiPaths.PORTAL + "/sites/{slug}")
class PortalAccessController {

    private final PortalAccessUseCases access;
    private final PortalRateLimiter rateLimiter;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;

    PortalAccessController(PortalAccessUseCases access, PortalRateLimiter rateLimiter, AccessLogger accessLogger,
                           MeterRegistry meterRegistry) {
        this.access = access;
        this.rateLimiter = rateLimiter;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
    }

    record CodeRequest(@NotBlank @Size(max = 20) String document, @NotBlank @Size(max = 20) String channel) {
    }

    record VerifyRequest(@Size(max = 10) String code, @Size(max = 40) String knowledgeAnswer) {
    }

    record LinkRequest(@NotBlank @Size(max = 100) String token) {
    }

    @GetMapping
    ResponseEntity<SiteView> site(@PathVariable String slug, HttpServletRequest request) {
        limit(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(access.site(slug));
    }

    @GetMapping("/logo")
    ResponseEntity<InputStreamResource> logo(@PathVariable String slug, HttpServletRequest request) {
        limit(request);
        LogoContent logo = access.logo(slug);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .eTag("\"" + logo.sha256() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new InputStreamResource(logo.content()));
    }

    @PostMapping("/access/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ChallengeView requestCode(@PathVariable String slug, @Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        limit(request);
        return access.requestCode(slug, new RequestCodeCommand(body.document(), body.channel()), client(request));
    }

    @PostMapping("/access/challenges/{challengeId}/verify")
    SessionGrant verify(@PathVariable String slug, @PathVariable UUID challengeId, @Valid @RequestBody VerifyRequest body,
                        HttpServletRequest request) {
        limit(request);
        return access.verify(slug, challengeId, new VerifyCommand(body.code(), body.knowledgeAnswer()), client(request));
    }

    @PostMapping("/access/link")
    LinkResult redeemLink(@PathVariable String slug, @Valid @RequestBody LinkRequest body, HttpServletRequest request) {
        limit(request);
        return access.redeemLink(slug, body.token(), client(request));
    }

    @ExceptionHandler(AccessLockedException.class)
    ResponseEntity<ProblemDetail> locked(AccessLockedException e) {
        long seconds = Math.max(1, Duration.between(Instant.now(), e.until()).toSeconds());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("code", "portal.access_locked");
        problem.setProperty("retryAfterSeconds", seconds);
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private void limit(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            meterRegistry.counter("portal.access.rate_limited").increment();
            accessLogger.log(AccessEvent.anonymous(AccessEventType.RATE_LIMITED, null, null,
                    Map.of("route", "portal")));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições");
        }
    }

    private static ClientInfo client(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return new ClientInfo(request.getRemoteAddr(), agent == null ? null : agent.length() > 300 ? agent.substring(0, 300) : agent);
    }
}
