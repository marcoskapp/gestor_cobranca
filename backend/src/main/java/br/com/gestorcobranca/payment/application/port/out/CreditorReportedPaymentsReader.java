package br.com.gestorcobranca.payment.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Leitura paginada dos pagamentos recebidos diretamente pelo credor ("Baixas informadas pelo credor"), sob RLS e com o
 * escopo por credor explícito na SQL.
 */
public interface CreditorReportedPaymentsReader {

    /**
     * @param allowedCreditors credores do escopo (nulo = todos os da assessoria; vazio = nenhum)
     * @param paidFrom         início (inclusive) do pagamento
     * @param paidUntil        fim (exclusivo) do pagamento
     * @param channel          INTEGRATION, CREDITOR_PORTAL, OPERATOR ou nulo
     * @param status           situação do pagamento ou nula
     */
    record Criteria(Set<UUID> allowedCreditors, UUID creditorId, Instant paidFrom, Instant paidUntil, String channel,
                    UUID tokenId, BigDecimal minAmount, BigDecimal maxAmount, String status) {
    }

    /** @param column coluna da ordenação (lista branca do serviço) */
    record Order(String column, boolean ascending) {
    }

    record Totals(long count, BigDecimal amount, BigDecimal effectiveAmount) {
    }

    /** Ids da página, na ordem pedida. */
    List<UUID> page(Criteria criteria, List<Order> order, long offset, int limit);

    Totals totals(Criteria criteria);
}
