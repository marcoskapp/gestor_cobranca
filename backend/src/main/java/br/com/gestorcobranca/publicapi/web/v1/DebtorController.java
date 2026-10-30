package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.AddContactCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.ContactDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.DebtorDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.TitleDto;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Devedores com títulos no credor do token (módulo debt). Minimização: nome e documento mascarado; contatos sempre
 * mascarados (o token nunca recebe {@code DEBTOR_SENSITIVE_VIEW}). Devedor sem título no credor = inexistente.
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/debtors")
@Tag(name = "Devedores", description = "Consulta mascarada e atualização de contatos")
class DebtorController {

    private final DebtorUseCases debtors;
    private final DebtorContactUseCases contacts;
    private final DebtTitleUseCases titles;
    private final PublicApiSupport support;

    DebtorController(DebtorUseCases debtors, DebtorContactUseCases contacts, DebtTitleUseCases titles,
                     PublicApiSupport support) {
        this.debtors = debtors;
        this.contacts = contacts;
        this.titles = titles;
        this.support = support;
    }

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('SCOPE_debtors:read')")
    @Operation(summary = "Localizar devedor pelo CPF/CNPJ", description = "POST para o documento não trafegar na URL "
            + "(logs de proxy). 404 se não houver devedor com títulos no credor.")
    DebtorDto search(@Valid @RequestBody DocumentRequest r) {
        return debtors.findByDocument(r.document()).map(DebtorDto::of)
                .filter(d -> hasTitlesInCreditor(d.id()))
                .orElseThrow(() -> new ResourceNotFoundException("debtor", "document"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_debtors:read')")
    @Operation(summary = "Devedor")
    DebtorDto get(@PathVariable UUID id) {
        own(id);
        return DebtorDto.of(debtors.get(id));
    }

    @GetMapping("/{id}/titles")
    @PreAuthorize("hasAuthority('SCOPE_titles:read')")
    @Operation(summary = "Títulos do devedor no credor")
    List<TitleDto> titles(@PathVariable UUID id) {
        own(id);
        return titles.listByDebtor(id).stream().filter(t -> t.creditorId().equals(support.creditorId()))
                .map(TitleDto::of).toList();
    }

    @GetMapping("/{id}/contacts")
    @PreAuthorize("hasAuthority('SCOPE_debtors:read')")
    @Operation(summary = "Contatos (mascarados)")
    List<ContactDto> contacts(@PathVariable UUID id) {
        own(id);
        return contacts.list(id).stream().map(ContactDto::of).toList();
    }

    @PostMapping("/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_debtors:write')")
    @Operation(summary = "Incluir contato", description = "Telefone (E.164 brasileiro), e-mail ou endereço. Valor "
            + "repetido para o mesmo devedor → 409 `contact.duplicate`. A resposta devolve o valor mascarado.")
    ContactDto addContact(@PathVariable UUID id, @Valid @RequestBody AddContactRequest r, HttpServletRequest http) {
        own(id);
        ContactDto dto = ContactDto.of(contacts.add(id, new AddContactCommand(r.type(), r.value(), r.label(),
                r.whatsapp() != null && r.whatsapp(), r.priority(), "MANUAL")));
        UsageRecords.add(http, 1);
        return dto;
    }

    @PostMapping("/{id}/contacts/{contactId}/invalidate")
    @PreAuthorize("hasAuthority('SCOPE_debtors:write')")
    @Operation(summary = "Invalidar contato", description = "Ex.: telefone de terceiro, e-mail inexistente.")
    ContactDto invalidate(@PathVariable UUID id, @PathVariable UUID contactId, @Valid @RequestBody InvalidateContactRequest r,
                          HttpServletRequest http) {
        own(id);
        ContactDto dto = ContactDto.of(contacts.markInvalid(id, contactId, r.reason()));
        UsageRecords.add(http, 1);
        return dto;
    }

    /** Devedor visível só se tiver título no credor do token. */
    private void own(UUID debtorId) {
        if (!hasTitlesInCreditor(debtorId)) {
            throw ResourceNotFoundException.of("debtor", debtorId);
        }
    }

    private boolean hasTitlesInCreditor(UUID debtorId) {
        try {
            return titles.listByDebtor(debtorId).stream().anyMatch(t -> t.creditorId().equals(support.creditorId()));
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    record DocumentRequest(@NotBlank @Size(max = 20) String document) {
    }

    record AddContactRequest(@NotBlank @Pattern(regexp = "PHONE|EMAIL|ADDRESS") String type,
                             @NotBlank @Size(max = 300) String value,
                             @Pattern(regexp = "MOBILE|HOME|WORK|OTHER") String label, Boolean whatsapp,
                             @Min(0) @Max(99) Integer priority) {
    }

    record InvalidateContactRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
