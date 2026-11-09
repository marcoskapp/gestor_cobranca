package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.QuickSearchQueries;
import br.com.gestorcobranca.collection.application.port.in.QuickSearchQueries.QuickSearchView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Busca rápida de devedor e caso (Ctrl+K). POST para que o termo (que pode ser CPF/CNPJ ou telefone) não trafegue na
 * URL, que fica em logs de proxy/ALB e no histórico do navegador; resposta sem cache.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class QuickSearchController {

    private final QuickSearchQueries search;

    QuickSearchController(QuickSearchQueries search) {
        this.search = search;
    }

    @PostMapping("/quick-search")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    ResponseEntity<QuickSearchView> search(@Valid @RequestBody QuickSearchRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(request.term()));
    }

    record QuickSearchRequest(@NotNull @Size(max = 80) String term) {
    }
}
