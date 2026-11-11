package br.com.gestorcobranca.analytics.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Saúde da política de negociação de um credor (decisão 1087): resultados por carteira × faixa de atraso e sugestões
 * explicáveis de revisão. Só agregados; devedores com marcador de vulnerabilidade ativo ficam fora; as sugestões nunca
 * alteram a política. Exige {@code POLICY_MANAGE} (quem altera a política) ou {@code REPORT_VIEW} (quem analisa
 * resultados) e o credor no escopo.
 */
public interface PolicyHealthQueries {

    PolicyHealthView health(PolicyHealthFilter filter);

    /**
     * @param from      início da safra (data de formalização, fuso de operação; nula = 180 dias antes do fim)
     * @param to        fim (nulo = hoje)
     * @param valueBand faixa de valor do acordo ({@code V0_1K}, {@code V1K_5K}, {@code V5K_20K}, {@code V20K_PLUS};
     *                  nula = todas); não se aplica às consultas e pedidos do portal (sem valor de acordo)
     */
    record PolicyHealthFilter(UUID creditorId, LocalDate from, LocalDate to, String valueBand) {
    }

    /**
     * @param minAgreements acordos mínimos do recorte para as sugestões sobre desconto
     * @param minMature     acordos maduros mínimos para as sugestões sobre quebra
     * @param minPortal     consultas mínimas do portal para as sugestões sobre o portal
     */
    record PolicyHealthView(UUID creditorId, LocalDate from, LocalDate to, String valueBand, Instant asOf,
                            SegmentHealth total, List<SegmentHealth> segments, List<SuggestionView> suggestions,
                            int minAgreements, int minMature, int minPortal) {
    }

    /**
     * @param earlyBreakRate        quebra em até 60 dias ÷ acordos maduros (nula sem maduros)
     * @param averageEnvelopeUsage  uso médio do desconto máximo, em % (0–100)
     * @param highEnvelopeShare     fatia dos acordos que usaram 90% ou mais do desconto máximo
     */
    record SegmentHealth(UUID portfolioId, String delayBucket, long agreements, long matureAgreements, long brokenEarly,
                         BigDecimal earlyBreakRate, BigDecimal agreedAmount, BigDecimal paidAmount,
                         BigDecimal paidOverAgreed, BigDecimal averageEnvelopeUsage, BigDecimal highEnvelopeShare,
                         long portalSimulations, long portalWithoutOffer, BigDecimal portalWithoutOfferShare,
                         long cannotAfford) {
    }

    /** @param kind HIGH_EARLY_BREAK, DISCOUNT_AT_LIMIT, DISCOUNT_UNUSED, PORTAL_WITHOUT_OFFER ou CANNOT_AFFORD */
    record SuggestionView(String kind, UUID portfolioId, String delayBucket) {
    }
}
