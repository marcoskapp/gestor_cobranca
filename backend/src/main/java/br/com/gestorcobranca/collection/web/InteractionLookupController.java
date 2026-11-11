package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.InteractionLocation;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Interação pelo id (fora da rota do caso): o link da monitoria volta ao caso em que o atendimento aconteceu. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/interactions")
class InteractionLookupController {

    private final InteractionUseCases interactions;

    InteractionLookupController(InteractionUseCases interactions) {
        this.interactions = interactions;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    InteractionLocation get(@PathVariable UUID id) {
        return interactions.locate(id);
    }
}
