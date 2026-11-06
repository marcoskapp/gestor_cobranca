package br.com.gestorcobranca.helpdesk.web;

import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases.ManualParametersView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parâmetros citados no manual, nos tours e nas novidades ({@code GET /api/v1/helpdesk/manual-parameters}): liberado
 * a todo usuário interno ({@code ACTOR_USER}), como o manual — nenhum perfil recebe 403 ao abrir a ajuda. Só números
 * de configuração da aplicação, iguais para todas as assessorias (nada da assessoria, de credor ou de devedor).
 * {@code no-store}, como as demais rotas do helpdesk: o frontend lê uma vez por sessão e reflete uma configuração
 * nova logo após o deploy.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/helpdesk/manual-parameters")
@PreAuthorize("hasAuthority('ACTOR_USER')")
class ManualParametersController {

    private final ManualParameterUseCases parameters;

    ManualParametersController(ManualParameterUseCases parameters) {
        this.parameters = parameters;
    }

    @GetMapping
    ResponseEntity<ManualParametersView> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(parameters.current());
    }
}
