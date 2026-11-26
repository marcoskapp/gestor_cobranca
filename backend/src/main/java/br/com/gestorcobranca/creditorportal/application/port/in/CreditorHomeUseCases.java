package br.com.gestorcobranca.creditorportal.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Início do portal do credor (fatia 7): os indicadores do credor e o "Aguardando você". O credor vem sempre da sessão.
 * Cada fonte (indicadores, repasse, contestações, demonstrativos, pagamentos) falha sozinha: a seção fica nula e a
 * fonte entra em {@code unavailable}, sem derrubar o restante. Records próprios (lista de campos permitidos).
 */
public interface CreditorHomeUseCases {

    /** @param period THIS_MONTH, LAST_MONTH, LAST_3_MONTHS ou THIS_YEAR */
    Overview overview(String period);

    /** Sem cache: as contagens e os itens saem dos mesmos filtros das listas de destino (RS-63). */
    Awaiting awaiting();

    /**
     * @param generatedAt instante em que o servidor montou a resposta ("Atualizado às")
     * @param dataUntil   "dados até" dos indicadores (nulo: ainda sem atualização)
     * @param unavailable fontes fora do ar nesta resposta: INDICATORS e/ou TRANSFER
     */
    record Overview(String period, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                    Instant generatedAt, Instant dataUntil, Recovery recovery, List<Month> monthly,
                    List<PortfolioShare> byPortfolio, Balance balance, Agreements agreements, Transfer transfer,
                    List<String> unavailable) {
    }

    /** @param paidToCreditor parte do recuperado paga diretamente ao credor (já dentro de {@code recovered}) */
    record Recovery(BigDecimal recovered, BigDecimal paidToCreditor, BigDecimal previousRecovered,
                    BigDecimal previousPaidToCreditor) {
    }

    /** @param month {@code AAAA-MM} */
    record Month(String month, BigDecimal recovered, BigDecimal paidToCreditor) {
    }

    /** @param others soma das demais carteiras ("Outros"); {@code portfolioId} e {@code name} nulos */
    record PortfolioShare(UUID portfolioId, String name, BigDecimal recovered, boolean others) {
    }

    record Balance(BigDecimal amount, long titles, LocalDate referenceDate) {
    }

    /** @param ratio fração (0 a 1, escala 4); nula sem acordo ativo; @param alert abaixo de 80% */
    record Agreements(long onTrack, long active, BigDecimal ratio, boolean alert, LocalDate referenceDate) {
    }

    /** Repasse estimado do período em aberto; @param direction AGENCY_TO_CREDITOR, CREDITOR_TO_AGENCY ou NONE */
    record Transfer(LocalDate periodStart, LocalDate asOf, LocalDate expectedPeriodEnd, BigDecimal netTransfer,
                    String direction, Instant calculatedAt) {
    }

    /**
     * @param total       itens que dependem do credor (todos, não só os devolvidos)
     * @param dueSoon     com prazo de hoje até 2 dias, ainda não vencido
     * @param overdue     com o prazo vencido
     * @param items       vencidos primeiro, depois pelo prazo, depois os sem prazo; até 50
     * @param unavailable fontes fora do ar: DISPUTES, STATEMENTS e/ou PAYMENTS
     */
    record Awaiting(int total, int dueSoon, int overdue, Counts counts, List<AwaitingItem> items,
                    List<String> unavailable) {
    }

    record Counts(int disputes, int statements, int rejectedPayments) {
    }

    /**
     * @param kind      DISPUTE, STATEMENT ou PAYMENT_REJECTED; só a seção do tipo vem preenchida
     * @param id        id do registro (a ficha da contestação ou do demonstrativo; o pagamento leva à lista)
     * @param dueOn     prazo (nulo no pagamento recusado)
     * @param youCanAct a pessoa da sessão pode responder (senão: "Aguardando o seu time")
     */
    record AwaitingItem(String kind, UUID id, LocalDate dueOn, boolean overdue, boolean dueSoon, boolean youCanAct,
                        AwaitingDispute dispute, AwaitingStatement statement, AwaitingPayment payment) {
    }

    /** @param type natureza da contestação */
    record AwaitingDispute(String protocol, String type) {
    }

    record AwaitingStatement(int sequenceNumber, LocalDate periodStart, LocalDate periodEnd) {
    }

    /** @param rejectionReason PROOF_INVALID, AMOUNT_OR_DATE_MISMATCH, WRONG_TITLES, DUPLICATE ou OTHER */
    record AwaitingPayment(LocalDate paidOn, BigDecimal amount, String rejectionReason, Instant decidedAt) {
    }
}
