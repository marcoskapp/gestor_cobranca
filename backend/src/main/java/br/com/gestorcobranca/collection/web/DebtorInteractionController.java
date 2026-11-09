package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.InteractionView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Histórico de interações do devedor (todos os casos nos credores do escopo), para a ficha do devedor. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/debtors/{debtorId}/interactions")
class DebtorInteractionController {

    private final InteractionUseCases interactions;

    DebtorInteractionController(InteractionUseCases interactions) {
        this.interactions = interactions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    PageResponse<InteractionView> list(@PathVariable UUID debtorId, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return interactions.listByDebtor(debtorId, PageQuery.of(page, size));
    }
}
