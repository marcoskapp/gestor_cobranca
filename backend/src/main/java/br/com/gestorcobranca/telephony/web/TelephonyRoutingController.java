package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.RouteView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.SaveRouteCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Roteamento entre contas de telefonia: uma regra por escopo (campanha, carteira, credor, padrão). */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony/routes")
class TelephonyRoutingController {

    private final TelephonyRoutingUseCases routing;

    TelephonyRoutingController(TelephonyRoutingUseCases routing) {
        this.routing = routing;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('TELEPHONY_MANAGE', 'TELEPHONY_SUPERVISE')")
    List<RouteView> list() {
        return routing.list();
    }

    /** Cria ou substitui a regra do escopo informado. */
    @PutMapping
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    RouteView save(@Valid @RequestBody SaveRequest request) {
        return routing.save(new SaveRouteCommand(request.scopeType(), request.scopeId(), request.primaryAccountId(),
                request.fallbackAccountId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    void delete(@PathVariable UUID id) {
        routing.delete(id);
    }

    record SaveRequest(@NotBlank @Size(max = 20) String scopeType, UUID scopeId, @NotNull UUID primaryAccountId,
                       UUID fallbackAccountId) {
    }
}
