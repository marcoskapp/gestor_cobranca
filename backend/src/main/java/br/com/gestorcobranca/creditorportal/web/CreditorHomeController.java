package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.Awaiting;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.Overview;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Início do portal do credor (fatia 7): indicadores e "Aguardando você". Só o ator {@code CREDITOR}; permissão e credor
 * conferidos nas portas dos donos. Sem parâmetro de credor; período numa lista fechada (outro valor → 400
 * {@code request.malformed}); limite por pessoa (429 com {@code Retry-After}); {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping(ApiPaths.CREDITOR)
@PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
class CreditorHomeController {

    /** Lista fechada do período (o texto nunca chega ao SQL). */
    enum Period { THIS_MONTH, LAST_MONTH, LAST_3_MONTHS, THIS_YEAR }

    private final CreditorHomeUseCases home;
    private final CreditorReadLimiter limiter;

    CreditorHomeController(CreditorHomeUseCases home, CreditorReadLimiter limiter) {
        this.home = home;
        this.limiter = limiter;
    }

    @GetMapping("/overview")
    ResponseEntity<Overview> overview(@RequestParam(defaultValue = "THIS_MONTH") Period period,
                                      HttpServletResponse http) {
        limiter.acquire("overview", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, http);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(home.overview(period.name()));
    }

    @GetMapping("/awaiting")
    ResponseEntity<Awaiting> awaiting(HttpServletResponse http) {
        limiter.acquire("awaiting", CreditorReadLimiter.AWAITING_LIMIT_PER_MINUTE, http);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(home.awaiting());
    }
}
