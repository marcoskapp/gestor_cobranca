package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactView;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.Recipients;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Contatos do credor para pedidos de contestação (canal formal com o credor). */
@RestController
@RequestMapping(ApiPaths.V1 + "/creditors/{creditorId}/dispute-contacts")
class CreditorDisputeContactController {

    private final CreditorDisputeContactUseCases contacts;

    CreditorDisputeContactController(CreditorDisputeContactUseCases contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    List<DisputeContactView> list(@PathVariable UUID creditorId) {
        return contacts.list(creditorId);
    }

    /** Destinatários que um pedido de contestação da carteira usaria (pré-visualização da tela da contestação). */
    @GetMapping("/recipients")
    @PreAuthorize("hasAuthority('CREDITOR_VIEW') or hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK')")
    Recipients recipients(@PathVariable UUID creditorId, @RequestParam(required = false) UUID portfolioId) {
        return contacts.recipients(creditorId, portfolioId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    DisputeContactView create(@PathVariable UUID creditorId, @Valid @RequestBody ContactRequest request) {
        return contacts.create(creditorId, request.toCommand());
    }

    @PutMapping("/{contactId}")
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    DisputeContactView update(@PathVariable UUID creditorId, @PathVariable UUID contactId,
                              @Valid @RequestBody ContactRequest request) {
        return contacts.update(creditorId, contactId, request.toCommand());
    }

    @PostMapping("/{contactId}/deactivate")
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    DisputeContactView deactivate(@PathVariable UUID creditorId, @PathVariable UUID contactId) {
        return contacts.deactivate(creditorId, contactId);
    }

    record ContactRequest(UUID portfolioId,
                          @NotBlank @Size(max = 150) String name,
                          @NotBlank @Email @Size(max = 200) String email,
                          @Size(max = 30) String phone,
                          @NotBlank @Pattern(regexp = "PRIMARY|COPY") String role) {

        DisputeContactCommand toCommand() {
            return new DisputeContactCommand(portfolioId, name, email, phone, role);
        }
    }
}
