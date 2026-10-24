package br.com.gestorcobranca.assistant.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Estado de negociação da sessão (jsonb), atualizado somente pelo servidor a partir das saídas das
 * ferramentas: ofertas apresentadas (com referência opaca para o modelo), proposta corrente, resumo
 * da dívida e fatos citáveis. O modelo nunca escreve aqui diretamente.
 */
public record SessionMemory(DebtSummary debt, UUID simulationId, List<OfferMemo> offers, ProposalMemo proposal,
                            AllowedFacts facts, String lastIntent) {

    public static final SessionMemory EMPTY = new SessionMemory(null, null, List.of(), null, AllowedFacts.EMPTY, null);

    public SessionMemory {
        offers = offers == null ? List.of() : List.copyOf(offers);
        facts = facts == null ? AllowedFacts.EMPTY : facts;
    }

    /** Resumo da dívida devolvido pelo motor (saldo atualizado na data). */
    public record DebtSummary(BigDecimal updatedTotal, int titles, long daysOverdue, LocalDate referenceDate) {
    }

    /**
     * Oferta devolvida pela simulação (valores do motor).
     *
     * @param ref             referência opaca usada pelo modelo (ex.: {@code O2})
     * @param discountRates   desconto por componente (%), na ordem principal, correção, juros, multa, encargos, honorários
     * @param valid           dentro da política
     * @param withinAuthority dentro da alçada do assistente
     */
    public record OfferMemo(String ref, UUID simulationId, int level, int installments, BigDecimal total,
                            BigDecimal downPayment, BigDecimal installmentAmount, LocalDate firstDueDate,
                            BigDecimal discountTotal, List<BigDecimal> discountRates, boolean valid,
                            boolean withinAuthority) {

        public OfferMemo {
            discountRates = discountRates == null ? List.of() : List.copyOf(discountRates);
        }
    }

    /** @param status situação no negotiation (DRAFT, HANDED_OFF, ACCEPTED, FORMALIZED…) */
    public record ProposalMemo(String ref, UUID proposalId, String status, int installments, BigDecimal total,
                               UUID agreementId) {
    }

    public Optional<OfferMemo> offer(String ref) {
        return offers.stream().filter(o -> o.ref().equalsIgnoreCase(ref == null ? "" : ref.trim())).findFirst();
    }

    public SessionMemory withDebt(DebtSummary summary) {
        return new SessionMemory(summary, simulationId, offers, proposal,
                facts.withAmounts(List.of(summary.updatedTotal())), lastIntent);
    }

    public SessionMemory withOffers(UUID simulation, List<OfferMemo> newOffers) {
        AllowedFacts f = facts;
        for (OfferMemo o : newOffers) {
            f = f.withAmounts(java.util.stream.Stream.of(o.total(), o.downPayment(), o.installmentAmount(), o.discountTotal())
                            .filter(java.util.Objects::nonNull).toList())
                    .withDates(o.firstDueDate() == null ? List.of() : List.of(o.firstDueDate()))
                    .withInstallments(List.of(o.installments()))
                    .withPercents(o.discountRates());
        }
        return new SessionMemory(debt, simulation, newOffers, proposal, f, lastIntent);
    }

    public SessionMemory withProposal(ProposalMemo memo) {
        return new SessionMemory(debt, simulationId, offers, memo,
                facts.withAmounts(List.of(memo.total())).withInstallments(List.of(memo.installments())), lastIntent);
    }

    public SessionMemory withFacts(AllowedFacts newFacts) {
        return new SessionMemory(debt, simulationId, offers, proposal, newFacts, lastIntent);
    }

    public SessionMemory withIntent(String intent) {
        return new SessionMemory(debt, simulationId, offers, proposal, facts, intent);
    }

    /** Nova etapa de atendimento (conversa devolvida ao assistente): ofertas e proposta são recalculadas. */
    public SessionMemory reset() {
        return EMPTY;
    }
}
