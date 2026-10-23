package br.com.gestorcobranca.legal.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Autorização formal do credor (etapa 3). A solicitação nasce na transição para
 * AWAITING_AUTHORIZATION (vias e custas da viabilidade) ou é aberta depois, para via ou orçamento
 * suplementar ({@code LEGAL_MANAGE}). O registro da decisão exige {@code LEGAL_AUTHORIZE} e, na
 * concessão, a evidência (documento do caso jurídico do tipo AUTHORIZATION_EVIDENCE).
 */
public interface CreditorAuthorizationUseCases {

    AuthorizationView request(UUID matterId, RequestCommand command);

    AuthorizationView decide(UUID authorizationId, DecisionCommand command);

    List<AuthorizationView> list(UUID matterId);

    record RequestCommand(Set<String> routes, BigDecimal budget, String note) {
    }

    /**
     * @param decision       GRANTED ou DENIED
     * @param routes         vias concedidas (subconjunto das solicitadas; ignorado na negativa)
     * @param channel        EMAIL, SIGNED_DOCUMENT, CREDITOR_PORTAL, LETTER ou OTHER
     * @param representative quem decidiu pelo credor
     */
    record DecisionCommand(String decision, Set<String> routes, BigDecimal approvedBudget, LocalDate decisionDate,
                           String channel, String representative, UUID evidenceDocumentId, String note) {
    }

    record AuthorizationView(UUID id, UUID matterId, String status, Set<String> requestedRoutes,
                             BigDecimal requestedBudget, String requestNote, Set<String> authorizedRoutes,
                             BigDecimal approvedBudget, LocalDate decisionDate, String channel,
                             String creditorRepresentative, UUID evidenceDocumentId, String decisionNote,
                             Instant requestedAt, UUID requestedBy, Instant decidedAt, UUID decidedBy) {
    }
}
