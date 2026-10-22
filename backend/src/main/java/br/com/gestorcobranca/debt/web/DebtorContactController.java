package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.AddContactCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.RevealedContact;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.UpdateContactCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Contatos do devedor. Valores sempre mascarados, exceto na revelação auditada. */
@RestController
@RequestMapping(ApiPaths.V1 + "/debtors/{debtorId}/contacts")
class DebtorContactController {

    private final DebtorContactUseCases contacts;

    DebtorContactController(DebtorContactUseCases contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    List<ContactView> list(@PathVariable UUID debtorId) {
        return contacts.list(debtorId);
    }

    @GetMapping("/{contactId}/value")
    @PreAuthorize("hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    RevealedContact reveal(@PathVariable UUID debtorId, @PathVariable UUID contactId) {
        return contacts.revealContact(debtorId, contactId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactView add(@PathVariable UUID debtorId, @Valid @RequestBody AddContactRequest request) {
        return contacts.add(debtorId, new AddContactCommand(request.type(), request.value(), request.label(),
                request.whatsapp(), request.priority(), request.source()));
    }

    @PutMapping("/{contactId}")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactView update(@PathVariable UUID debtorId, @PathVariable UUID contactId,
                       @Valid @RequestBody UpdateContactRequest request) {
        return contacts.update(debtorId, contactId, new UpdateContactCommand(request.label(), request.whatsapp(),
                request.priority()));
    }

    @PostMapping("/{contactId}/invalidate")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactView markInvalid(@PathVariable UUID debtorId, @PathVariable UUID contactId,
                            @Valid @RequestBody ReasonRequest request) {
        return contacts.markInvalid(debtorId, contactId, request.reason());
    }

    @PostMapping("/{contactId}/validate")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    ContactView markValid(@PathVariable UUID debtorId, @PathVariable UUID contactId) {
        return contacts.markValid(debtorId, contactId);
    }

    record AddContactRequest(@NotBlank @Pattern(regexp = "PHONE|EMAIL|ADDRESS") String type,
                             @NotBlank @Size(max = 500) String value,
                             @Pattern(regexp = "MOBILE|HOME|WORK|OTHER") String label,
                             boolean whatsapp,
                             @Min(0) @Max(1000) Integer priority,
                             @Pattern(regexp = "MANUAL|DEBTOR") String source) {
    }

    record UpdateContactRequest(@NotBlank @Pattern(regexp = "MOBILE|HOME|WORK|OTHER") String label,
                                boolean whatsapp,
                                @NotNull @Min(0) @Max(1000) Integer priority) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
