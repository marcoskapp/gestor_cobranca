package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.util.Objects;

/**
 * Quando um pagamento externo (informado por uma pessoa) exige aprovação de outra pessoa. Além do
 * valor do próprio pagamento, valem limites <strong>acumulados</strong> numa janela de tempo, para que
 * fracionar um valor alto em vários pagamentos pequenos não dispense a segunda pessoa:
 * <ul>
 *   <li>por destino: pagamentos externos do mesmo devedor no mesmo credor (cobre parcela, acordo e
 *       títulos, que pertencem sempre a um devedor e um credor);</li>
 *   <li>por quem informa: tudo o que o mesmo usuário informou.</li>
 * </ul>
 * Contam os pagamentos externos informados na janela e confirmados <strong>sem</strong> revisão (os
 * revisados já tiveram a segunda pessoa).
 */
public final class ExternalReviewPolicy {

    public enum Reason { AMOUNT, DESTINATION_CUMULATIVE, INFORMER_CUMULATIVE }

    /**
     * @param perPayment     acima deste valor o pagamento sozinho já exige revisão
     * @param perDestination soma máxima confirmada sem revisão para o mesmo devedor/credor na janela
     * @param perInformer    soma máxima confirmada sem revisão por quem informa na janela
     */
    public record Limits(Money perPayment, Money perDestination, Money perInformer) {

        public Limits {
            Objects.requireNonNull(perPayment, "perPayment");
            Objects.requireNonNull(perDestination, "perDestination");
            Objects.requireNonNull(perInformer, "perInformer");
        }
    }

    /** @param reason nulo quando não exige revisão */
    public record Decision(boolean reviewRequired, Reason reason) {

        static final Decision AUTO = new Decision(false, null);
    }

    private ExternalReviewPolicy() {
    }

    /**
     * @param destinationSoFar soma já confirmada sem revisão para o mesmo devedor/credor na janela
     * @param informerSoFar    soma já confirmada sem revisão do mesmo usuário na janela
     */
    public static Decision decide(Money amount, Money destinationSoFar, Money informerSoFar, Limits limits) {
        if (amount.isGreaterThan(limits.perPayment())) {
            return new Decision(true, Reason.AMOUNT);
        }
        if (destinationSoFar.plus(amount).isGreaterThan(limits.perDestination())) {
            return new Decision(true, Reason.DESTINATION_CUMULATIVE);
        }
        if (informerSoFar.plus(amount).isGreaterThan(limits.perInformer())) {
            return new Decision(true, Reason.INFORMER_CUMULATIVE);
        }
        return Decision.AUTO;
    }
}
