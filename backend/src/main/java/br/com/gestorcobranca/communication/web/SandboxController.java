package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.SandboxUseCases;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Simulação de mensagem recebida (somente com o sandbox habilitado; local/teste). */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/sandbox")
class SandboxController {

    private final SandboxUseCases sandbox;

    SandboxController(SandboxUseCases sandbox) {
        this.sandbox = sandbox;
    }

    @PostMapping("/inbound")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('CHANNEL_MANAGE')")
    void inbound(@Valid @RequestBody InboundRequest request) {
        sandbox.simulateInbound(request.channelAccountId(), request.from(), request.text());
    }

    record InboundRequest(@NotNull UUID channelAccountId, @NotBlank @Size(max = 320) String from,
                          @NotBlank @Size(max = 4096) String text) {
    }
}
