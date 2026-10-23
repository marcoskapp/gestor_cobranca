package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Avisos em tempo real da caixa de entrada por Server-Sent Events, autenticados pelo mesmo bearer
 * token das demais chamadas (o cliente usa {@code fetch} com cabeçalho {@code Authorization}, não
 * {@code EventSource}, para o token não ir na URL). Eventos: {@code ready}, {@code conversation}
 * ({@code conversationId}), {@code resync}, {@code expired}, {@code unauthorized} e
 * {@code reconnect}; comentários {@code ping} mantêm a conexão viva atrás do ALB.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/inbox/stream")
class InboxStreamController {

    private final InboxStreams streams;

    InboxStreamController(InboxStreams streams) {
        this.streams = streams;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    SseEmitter stream() {
        return streams.open((ActorAuthentication) SecurityContextHolder.getContext().getAuthentication());
    }
}
