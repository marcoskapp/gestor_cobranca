package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases.ActivityPage;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Minha atividade" (fatia 7). {@code scope=own} (padrão) ou {@code team} (só com {@code CREDITOR_ACTIVITY_VIEW}, senão
 * 403 {@code ACCESS_DENIED}); {@code before} = cursor opaco da página anterior; {@code size} de 1 a 50. Sem parâmetro de
 * credor nem de pessoa; limite por pessoa; {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping(ApiPaths.CREDITOR + "/activity")
@PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
class CreditorActivityController {

    private static final java.util.regex.Pattern CURSOR = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private final CreditorActivityPortalUseCases activity;
    private final CreditorReadLimiter limiter;

    CreditorActivityController(CreditorActivityPortalUseCases activity, CreditorReadLimiter limiter) {
        this.activity = activity;
        this.limiter = limiter;
    }

    @GetMapping
    ResponseEntity<ActivityPage> list(@RequestParam(defaultValue = "own") String scope,
                                      @RequestParam(required = false) String before,
                                      @RequestParam(defaultValue = "20") int size, HttpServletResponse http) {
        limiter.acquire("activity", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, http);
        if (!scope.equals("own") && !scope.equals("team")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope: own ou team");
        }
        if (size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size: de 1 a 50");
        }
        if (before != null && !CURSOR.matcher(before).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before: cursor inválido");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(activity.activity(scope.equals("team"), before, size));
    }
}
