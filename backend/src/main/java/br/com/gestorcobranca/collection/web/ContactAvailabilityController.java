package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries;
import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries.ContactAvailability;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "Posso contatar agora?" na estação de cobrança: decisão do porteiro de contato por canal para o
 * devedor e o credor do caso, sem registrar tentativa e sem expor a política de contato.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class ContactAvailabilityController {

    private final ContactAvailabilityQueries queries;

    ContactAvailabilityController(ContactAvailabilityQueries queries) {
        this.queries = queries;
    }

    /**
     * @param channel   PHONE, WHATSAPP, SMS ou EMAIL (vazio = todos)
     * @param contactId contato do devedor que se pretende usar (opcional)
     */
    @GetMapping("/cases/{id}/contact-availability")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    ContactAvailability check(@PathVariable UUID id, @RequestParam(required = false) String channel,
                              @RequestParam(required = false) UUID contactId) {
        return queries.check(id, channel, contactId);
    }
}
