package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Envio de mensagens pelo operador (201 com {@code QUEUED}, ou {@code FAILED/CONTACT_DENIED} quando
 * o porteiro já nega). Cabeçalho {@code Idempotency-Key} opcional para reenvio seguro.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/messages")
class MessageController {

    private final MessageUseCases messages;

    MessageController(MessageUseCases messages) {
        this.messages = messages;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    MessageView send(@RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey,
                     @Valid @RequestBody SendRequest request) {
        return messages.send(new SendMessageCommand(request.conversationId(), request.debtorId(), request.creditorId(),
                request.caseId(), request.channel(), request.contactId(), request.templateCode(), request.variables(),
                request.text(), null, idempotencyKey));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    MessageView get(@PathVariable UUID id) {
        return messages.get(id);
    }

    record SendRequest(UUID conversationId, UUID debtorId, UUID creditorId, UUID caseId, @Size(max = 20) String channel,
                       UUID contactId, @Size(max = 60) String templateCode, @Size(max = 20) Map<String, String> variables,
                       @Size(max = 4096) String text) {
    }
}
