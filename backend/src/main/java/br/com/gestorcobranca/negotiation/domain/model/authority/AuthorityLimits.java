package br.com.gestorcobranca.negotiation.domain.model.authority;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Parâmetros de alçada de um ator, extraídos do seu {@link AuthorityProfile}. O limite efetivo de
 * uma negociação é sempre o mínimo entre estes valores e o envelope da política.
 *
 * @param maxDiscountShare    fração (%) do desconto máximo da política que o ator pode conceder
 * @param maxAgreementAmount  valor máximo do acordo (soma dos pagamentos); nulo = sem limite próprio
 * @param minDownPaymentShare entrada mínima própria (% do valor negociado), somada à exigência da política pelo máximo
 */
public record AuthorityLimits(UUID profileId, String profileCode, int maxConcessionLevel, BigDecimal maxDiscountShare,
                              int maxInstallments, Money maxAgreementAmount, BigDecimal minDownPaymentShare,
                              boolean canFormalize, boolean canApprove, int maxRenegotiations) {
}
