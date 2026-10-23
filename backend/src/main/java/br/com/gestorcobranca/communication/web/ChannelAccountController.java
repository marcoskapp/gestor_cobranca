package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.AccountView;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.UpdateAccountCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.Map;
import java.util.UUID;

/** Contas de canal. Credenciais só entram (criação e rotação); nunca são devolvidas. */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/channels")
class ChannelAccountController {

    private final ChannelAccountUseCases accounts;

    ChannelAccountController(ChannelAccountUseCases accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    List<AccountView> list() {
        return accounts.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return accounts.create(new CreateAccountCommand(request.channel(), request.provider(), request.creditorId(),
                request.name(), request.senderAddress(), request.senderName(), request.replyTo(), request.credentials()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView update(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
        return accounts.update(id, new UpdateAccountCommand(request.name(), request.senderAddress(), request.senderName(),
                request.replyTo()));
    }

    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView rotate(@PathVariable UUID id, @Valid @RequestBody CredentialsRequest request) {
        return accounts.rotateCredentials(id, request.credentials());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView activate(@PathVariable UUID id) {
        return accounts.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    AccountView deactivate(@PathVariable UUID id) {
        return accounts.deactivate(id);
    }

    record CreateAccountRequest(@NotBlank @Size(max = 20) String channel, @NotBlank @Size(max = 30) String provider,
                                UUID creditorId, @NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 320) String senderAddress, @Size(max = 120) String senderName,
                                @Size(max = 320) String replyTo, @Size(max = 10) Map<String, String> credentials) {
    }

    record UpdateAccountRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 320) String senderAddress,
                                @Size(max = 120) String senderName, @Size(max = 320) String replyTo) {
    }

    record CredentialsRequest(@NotNull @Size(max = 10) Map<String, String> credentials) {
    }
}
