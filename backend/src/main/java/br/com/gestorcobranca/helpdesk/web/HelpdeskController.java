package br.com.gestorcobranca.helpdesk.web;

import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.AnswerView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.AskCommand;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.ConversationSummary;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.ConversationView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.FeedbackCommand;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.MessageView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.StatusView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskExceptions;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Chat de suporte do usuário da sessão ({@code ACTOR_USER}: liberado a todos os perfis de usuário interno; o devedor
 * do portal nunca chega aqui). Não há id de usuário na URL: conversas, avaliações e chamados são sempre os da
 * própria pessoa (os de outra respondem 404). Respostas {@code no-store}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/helpdesk")
@PreAuthorize("hasAuthority('ACTOR_USER')")
class HelpdeskController {

    private static final URI TYPE_BASE = URI.create("https://gestorcobranca.com.br/problems/");

    private final HelpdeskChatUseCases chat;
    private final HelpdeskTicketUseCases tickets;

    HelpdeskController(HelpdeskChatUseCases chat, HelpdeskTicketUseCases tickets) {
        this.chat = chat;
        this.tickets = tickets;
    }

    /**
     * @param screen chave da tela atual no manual (só a chave: o texto do manual vem do servidor)
     */
    record AskRequest(UUID conversationId,
                      @Size(max = 64) @Pattern(regexp = "[a-z0-9]+([.-][a-z0-9]+)*") String screen,
                      @NotBlank @Size(max = 2000) String text) {
    }

    record FeedbackRequest(@NotBlank @Pattern(regexp = "UP|DOWN") String rating,
                           @Pattern(regexp = "INCORRECT|NOT_HELPFUL|INCOMPLETE|OUT_OF_DATE|OTHER") String reason) {
    }

    @GetMapping("/status")
    ResponseEntity<StatusView> status() {
        return noStore(chat.status());
    }

    @PostMapping("/messages")
    ResponseEntity<AnswerView> ask(@Valid @RequestBody AskRequest request) {
        return noStore(chat.ask(new AskCommand(request.conversationId(), request.screen(), request.text())));
    }

    @GetMapping("/conversations")
    ResponseEntity<List<ConversationSummary>> recent(@RequestParam(defaultValue = "10") int limit) {
        return noStore(chat.recent(limit));
    }

    @GetMapping("/conversations/{id}")
    ResponseEntity<ConversationView> conversation(@PathVariable UUID id) {
        return noStore(chat.conversation(id));
    }

    @PostMapping("/messages/{id}/feedback")
    ResponseEntity<MessageView> feedback(@PathVariable UUID id, @Valid @RequestBody FeedbackRequest request) {
        return noStore(chat.feedback(id, new FeedbackCommand(request.rating(), request.reason())));
    }

    @GetMapping("/tickets/{id}")
    ResponseEntity<TicketView> ticket(@PathVariable UUID id) {
        return noStore(tickets.get(id));
    }

    @PostMapping("/tickets/{id}/confirm")
    ResponseEntity<TicketView> confirm(@PathVariable UUID id) {
        return noStore(tickets.confirm(id));
    }

    @PostMapping("/tickets/{id}/cancel")
    ResponseEntity<TicketView> cancel(@PathVariable UUID id) {
        return noStore(tickets.cancel(id));
    }

    @ExceptionHandler(HelpdeskExceptions.LimitReached.class)
    ProblemDetail limit(HelpdeskExceptions.LimitReached e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, e.code(), e.getMessage());
    }

    @ExceptionHandler(HelpdeskExceptions.Unavailable.class)
    ProblemDetail unavailable(HelpdeskExceptions.Unavailable e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.getMessage());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.replace('.', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return problem;
    }
}
