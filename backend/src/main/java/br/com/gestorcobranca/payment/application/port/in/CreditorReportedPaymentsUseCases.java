package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Baixas informadas pelo credor", aba dos pagamentos (H07 parte 2b): pagamentos externos recebidos diretamente pelo
 * credor (origem {@code CREDITOR_DIRECT} ou {@code CREDITOR_REPORT}), qualquer que seja o canal de quem informou
 * (integração do credor, pessoa do credor no portal ou operador). Somente leitura, sem exportação.
 * <p>
 * {@code PAYMENT_VIEW}; ator {@code CREDITOR} e {@code INTEGRATION} recusados (403). Escopo por credor nas linhas e nos
 * totais (RS-94); o token e o credor do filtro são só condições dentro do escopo (RS-95). O nome do devedor só vai a quem
 * tem {@code DEBTOR_VIEW}; os títulos a quem tem {@code DEBTOR_VIEW} ou {@code CASE_VIEW}; o acordo a quem tem
 * {@code AGREEMENT_VIEW}; o token a quem tem {@code PUBLIC_API_TOKEN_VIEW} (RS-96, RS-97).
 */
public interface CreditorReportedPaymentsUseCases {

    /** Período máximo da consulta, em dias (RS-105). */
    int MAX_PERIOD_DAYS = 366;

    /** Tamanho máximo da página. */
    int MAX_PAGE_SIZE = 100;

    ReportedPaymentsPage search(ReportedPaymentFilter filter, PageQuery page);

    /**
     * @param from      primeiro dia do pagamento (obrigatório; fuso de operação)
     * @param to        último dia do pagamento (obrigatório; até {@value #MAX_PERIOD_DAYS} dias depois de {@code from})
     * @param channel   INTEGRATION, CREDITOR_PORTAL ou OPERATOR (nulo = todos)
     * @param tokenId   só os pagamentos informados por este token de integração (ignorado sem
     *                  {@code PUBLIC_API_TOKEN_VIEW})
     * @param minAmount valor mínimo (inclusive)
     * @param maxAmount valor máximo (inclusive)
     * @param status    INFORMED, CONFIRMED, RECONCILED, REJECTED ou REVERSED (nulo = todas)
     */
    record ReportedPaymentFilter(LocalDate from, LocalDate to, UUID creditorId, String channel, UUID tokenId,
                                    BigDecimal minAmount, BigDecimal maxAmount, String status) {
    }

    /**
     * Token de integração que informou (só para {@code PUBLIC_API_TOKEN_VIEW}).
     *
     * @param found falso quando o token não é mais encontrado (o nome e o prefixo vêm nulos)
     * @param state ACTIVE, PAUSED (pela plataforma), REVOKED, EXPIRED ou NOT_FOUND, no momento da consulta
     */
    record TokenRef(UUID id, String name, String prefix, boolean revoked, boolean found, String state) {
    }

    /**
     * @param debtorName         nome de exibição (nulo sem {@code DEBTOR_VIEW}); anonimizado = nome substituto
     * @param agreementId        acordo de destino (nulo sem {@code AGREEMENT_VIEW} ou quando o destino são títulos)
     * @param titleIds           títulos do destino (vazio sem {@code DEBTOR_VIEW} e sem {@code CASE_VIEW})
     * @param titleCount         quantidade de títulos do destino (sempre)
     * @param channel            INTEGRATION, CREDITOR_PORTAL ou OPERATOR
     * @param token              token que informou (canal INTEGRATION e {@code PUBLIC_API_TOKEN_VIEW}); nulo nos demais
     * @param evidenceUnverified comprovante guardado que ninguém da assessoria conferiu nem passou pela verificação de
     *                           malware (portal com veredito diferente de limpo, integração); falso sem comprovante
     *                           (anonimizado)
     */
    record ReportedPaymentItem(UUID paymentId, Instant paidAt, Instant informedAt, UUID creditorId, UUID debtorId,
                          String debtorName, boolean debtorAnonymized, UUID agreementId, List<UUID> titleIds,
                          int titleCount, BigDecimal amount, String origin, String channel, TokenRef token,
                          String status, boolean evidenceAvailable, boolean evidenceUnverified) {

        public ReportedPaymentItem {
            titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
        }
    }

    /**
     * Totais com o mesmo filtro e o mesmo escopo das linhas.
     *
     * @param amount          soma de todos os pagamentos filtrados
     * @param effectiveAmount soma dos confirmados e conciliados
     */
    record Totals(long count, BigDecimal amount, BigDecimal effectiveAmount) {
    }

    record ReportedPaymentsPage(PageResponse<ReportedPaymentItem> items, Totals totals) {
    }
}
