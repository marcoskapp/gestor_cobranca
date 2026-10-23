package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Linha do tempo de mensagens do devedor em todos os canais (caixa de entrada): uma única
 * consulta paginada em vez de montar a lista conversa a conversa no cliente.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/debtors/{debtorId}/messages")
class DebtorTimelineController {

    private final MessageUseCases messages;

    DebtorTimelineController(MessageUseCases messages) {
        this.messages = messages;
    }

    /** @param creditorId somente as conversas deste credor (opcional) */
    @GetMapping
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    PageResponse<MessageView> timeline(@PathVariable UUID debtorId, @RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return messages.listByDebtor(debtorId, creditorId, PageQuery.of(page, size));
    }
}
