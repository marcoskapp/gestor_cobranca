package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta do porteiro pelo operador: "posso contatar este devedor agora por este canal?".
 * Não registra tentativa; o registro é feito pelos módulos que efetivamente contatam.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/contact-gate")
class ContactGateController {

    private final ContactGate gate;

    ContactGateController(ContactGate gate) {
        this.gate = gate;
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    ContactDecision evaluate(@Valid @RequestBody EvaluateRequest request) {
        return gate.evaluate(ContactRequest.of(request.debtorId(), request.creditorId(), request.channel(),
                request.contactValue(), request.at(), "OPERATOR"));
    }

    record EvaluateRequest(@NotNull UUID debtorId, @NotNull UUID creditorId, @NotBlank @Size(max = 20) String channel,
                           @Size(max = 254) String contactValue, Instant at) {
    }
}
