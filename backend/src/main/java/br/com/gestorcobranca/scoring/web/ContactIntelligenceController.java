package br.com.gestorcobranca.scoring.web;

import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases.ContactInsightsView;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases.ContactProfileView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Inteligência de contato: melhor forma de contato do devedor e painel da carteira (só orientam). */
@RestController
@RequestMapping(ApiPaths.V1 + "/scoring")
@Tag(name = "Scoring — inteligência de contato", description = "Contato, canal e horário que mais respondem")
class ContactIntelligenceController {

    private final ContactIntelligenceUseCases contacts;

    ContactIntelligenceController(ContactIntelligenceUseCases contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/debtors/{debtorId}/contact-profile")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Perfil de contato do devedor: contatos pela taxa ajustada, melhor horário e canal preferido")
    ContactProfileView profile(@PathVariable UUID debtorId, @RequestParam(required = false) UUID caseId) {
        return contacts.profile(debtorId, caseId);
    }

    @GetMapping("/contact-insights")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Painel da carteira: atendimento por dia e horário, tentativas até o atendimento, canais e "
            + "contatos até o acordo cumprido")
    ContactInsightsView insights(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) UUID creditorId,
                                 @RequestParam(required = false) UUID portfolioId,
                                 @RequestParam(required = false) String personType) {
        return contacts.insights(from, to, creditorId, portfolioId, personType);
    }
}
