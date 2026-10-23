package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.AccountView;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.UpdateAccountCommand;
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
import java.util.UUID;

/** Contas Pix (PSP) da assessoria e dos credores. Segredos e certificado nunca são devolvidos. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/pix-accounts")
@PreAuthorize("hasAuthority('INTEGRATION_MANAGE')")
class PixAccountController {

    private final PixAccountUseCases accounts;

    PixAccountController(PixAccountUseCases accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<AccountView> list() {
        return accounts.list();
    }

    @GetMapping("/{id}")
    AccountView get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountView create(@Valid @RequestBody CreateRequest request) {
        return accounts.create(new CreateAccountCommand(request.creditorId(), request.name(), request.provider(),
                request.pixKey(), request.apiBaseUrl(), request.tokenUrl(), request.webhookAuth(),
                request.webhookFingerprints(), request.credentials().command()));
    }

    @PutMapping("/{id}")
    AccountView update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return accounts.update(id, new UpdateAccountCommand(request.name(), request.pixKey(), request.apiBaseUrl(),
                request.tokenUrl(), request.webhookAuth(), request.webhookFingerprints()));
    }

    @PutMapping("/{id}/credentials")
    AccountView rotate(@PathVariable UUID id, @Valid @RequestBody CredentialsRequest request) {
        return accounts.rotateCredentials(id, request.command());
    }

    @PostMapping("/{id}/webhook")
    AccountView registerWebhook(@PathVariable UUID id) {
        return accounts.registerWebhook(id);
    }

    @PostMapping("/{id}/activate")
    AccountView activate(@PathVariable UUID id) {
        return accounts.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    AccountView deactivate(@PathVariable UUID id) {
        return accounts.deactivate(id);
    }

    record CreateRequest(UUID creditorId, @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String provider,
                         @NotBlank @Size(max = 77) String pixKey, @NotBlank @Size(max = 500) String apiBaseUrl,
                         @NotBlank @Size(max = 500) String tokenUrl, @NotBlank @Size(max = 10) String webhookAuth,
                         @Size(max = 10) List<@Size(max = 100) String> webhookFingerprints,
                         @NotNull @Valid CredentialsRequest credentials) {
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 77) String pixKey,
                         @NotBlank @Size(max = 500) String apiBaseUrl, @NotBlank @Size(max = 500) String tokenUrl,
                         @NotBlank @Size(max = 10) String webhookAuth,
                         @Size(max = 10) List<@Size(max = 100) String> webhookFingerprints) {
    }

    record CredentialsRequest(@Size(max = 500) String clientId, @Size(max = 500) String clientSecret,
                              @Size(max = 500) String webhookSecret, @Size(max = 22000) String certificateBase64,
                              @Size(max = 500) String certificatePassword) {

        CredentialsCommand command() {
            return new CredentialsCommand(clientId, clientSecret, webhookSecret, certificateBase64, certificatePassword);
        }

        @Override
        public String toString() {
            return "CredentialsRequest[***]";
        }
    }
}
