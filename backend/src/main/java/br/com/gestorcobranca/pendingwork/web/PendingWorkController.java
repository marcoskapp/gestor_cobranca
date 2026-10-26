package br.com.gestorcobranca.pendingwork.web;

import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.PendingWorkSummary;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pendências do usuário da sessão. Não há id de usuário na URL: sempre o da sessão. Cada item já
 * respeita permissões, escopo por credor e segregação de funções da tela de destino.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/me/pending-work")
@PreAuthorize("hasAuthority('ACTOR_USER')")
class PendingWorkController {

    private final PendingWorkUseCases pendingWork;

    PendingWorkController(PendingWorkUseCases pendingWork) {
        this.pendingWork = pendingWork;
    }

    @GetMapping
    ResponseEntity<PendingWorkSummary> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pendingWork.mine());
    }
}
