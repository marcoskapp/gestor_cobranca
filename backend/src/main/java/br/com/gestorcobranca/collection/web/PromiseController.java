package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases.PromiseView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Promessas de pagamento (registradas pela tabulação de promessa nas interações). */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class PromiseController {

    private final PromiseUseCases promises;

    PromiseController(PromiseUseCases promises) {
        this.promises = promises;
    }

    @GetMapping("/cases/{caseId}/promises")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    List<PromiseView> byCase(@PathVariable UUID caseId) {
        return promises.listByCase(caseId);
    }

    @GetMapping("/promises/{id}")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    PromiseView get(@PathVariable UUID id) {
        return promises.get(id);
    }

    @PostMapping("/promises/{id}/cancel")
    @PreAuthorize("hasAuthority('INTERACTION_REGISTER')")
    PromiseView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return promises.cancel(id, request.reason());
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
