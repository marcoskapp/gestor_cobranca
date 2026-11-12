package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.ContactNoveltyUseCases;
import br.com.gestorcobranca.debt.application.port.in.ContactNoveltyUseCases.ContactNoveltySettingsView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prazo da janela de novidade dos contatos recebidos na carga (Configurações › Contatos recebidos). */
@RestController
@RequestMapping(ApiPaths.V1 + "/debtors/contact-novelty-settings")
class ContactNoveltyController {

    private final ContactNoveltyUseCases novelty;

    ContactNoveltyController(ContactNoveltyUseCases novelty) {
        this.novelty = novelty;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactNoveltySettingsView settings() {
        return novelty.settings();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactNoveltySettingsView update(@Valid @RequestBody SettingsRequest request) {
        return novelty.updateSettings(request.windowDays());
    }

    record SettingsRequest(@NotNull @Min(1) @Max(180) Integer windowDays) {
    }
}
