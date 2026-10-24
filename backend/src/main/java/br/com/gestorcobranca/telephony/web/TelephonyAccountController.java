package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.AccountView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.UpdateAccountCommand;
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

/** Contas do provedor de telefonia (credenciais nunca devolvidas). */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony/accounts")
class TelephonyAccountController {

    private final TelephonyAccountUseCases accounts;

    TelephonyAccountController(TelephonyAccountUseCases accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView create(@Valid @RequestBody CreateRequest request) {
        return accounts.create(new CreateAccountCommand(request.provider(), request.creditorId(), request.name(),
                request.callerId(), request.recordingEnabled(), request.recordingNotice(), request.credentials()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return accounts.update(id, new UpdateAccountCommand(request.name(), request.callerId(), request.recordingEnabled(),
                request.recordingNotice()));
    }

    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView rotate(@PathVariable UUID id, @RequestBody @NotNull @Size(max = 20) Map<String, String> credentials) {
        return accounts.rotateCredentials(id, credentials);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView activate(@PathVariable UUID id) {
        return accounts.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView deactivate(@PathVariable UUID id) {
        return accounts.deactivate(id);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    List<AccountView> list() {
        return accounts.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    AccountView get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    record CreateRequest(@NotBlank @Size(max = 30) String provider, UUID creditorId, @NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 20) String callerId, boolean recordingEnabled, boolean recordingNotice,
                         @Size(max = 20) Map<String, String> credentials) {
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String callerId,
                         boolean recordingEnabled, boolean recordingNotice) {
    }
}
