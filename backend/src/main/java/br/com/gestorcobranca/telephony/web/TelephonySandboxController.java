package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.port.in.TelephonySandboxUseCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Simulação de eventos do provedor (somente com o sandbox habilitado; local/teste). */
@RestController
@RequestMapping(ApiPaths.V1 + "/telephony/sandbox")
class TelephonySandboxController {

    private final TelephonySandboxUseCases sandbox;

    TelephonySandboxController(TelephonySandboxUseCases sandbox) {
        this.sandbox = sandbox;
    }

    @PostMapping("/calls/{callId}/events")
    @PreAuthorize("hasAuthority('TELEPHONY_USE') or hasAuthority('TELEPHONY_MANAGE')")
    Map<String, String> event(@PathVariable UUID callId, @Valid @RequestBody EventRequest request) {
        return Map.of("outcome", sandbox.simulateCallEvent(callId, request.type(), request.amd(), request.hangupBy()));
    }

    @PostMapping("/inbound")
    @PreAuthorize("hasAuthority('TELEPHONY_MANAGE')")
    Map<String, UUID> inbound(@Valid @RequestBody InboundRequest request) {
        return Map.of("callId", sandbox.simulateInbound(request.accountId(), request.from()));
    }

    record EventRequest(@NotBlank @Size(max = 30) String type, @Size(max = 10) String amd,
                        @Size(max = 10) String hangupBy) {
    }

    record InboundRequest(@NotNull UUID accountId, @NotBlank @Size(max = 32) String from) {
    }
}
