package br.com.gestorcobranca.debt.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Leitura da aba "Pela carga do credor", sob RLS e com o escopo por credor explícito na SQL. */
public interface CreditorReportedTitlesReader {

    /**
     * @param allowedCreditors credores do escopo (nulo = todos; vazio = nenhum)
     * @param from             início (inclusive)
     * @param until            fim (exclusivo)
     * @param situation        SETTLED_BY_IMPORT, REPORTED_PAID, REPORTED_CANCELLED ou nulo
     */
    record Criteria(Set<UUID> allowedCreditors, UUID creditorId, Instant from, Instant until, UUID importBatchId,
                    String situation) {
    }

    record Order(String column, boolean ascending) {
    }

    record Row(UUID titleId, UUID creditorId, UUID portfolioId, UUID debtorId, String externalId,
               Integer installmentNumber, String titleStatus, String situation, UUID importBatchId,
               Instant occurredAt, BigDecimal amount) {
    }

    record Totals(long count, BigDecimal amount) {
    }

    List<Row> page(Criteria criteria, List<Order> order, long offset, int limit);

    Totals totals(Criteria criteria);
}
