package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

/**
 * Parâmetros da oferta gerada numa etapa (porta {@code CampaignOfferPort}): forma de pagamento
 * pedida, nível da escada de concessões e validade. A oferta efetiva é sempre a que o motor do
 * negotiation devolver para a alçada da campanha — nada aqui contorna o {@code NegotiationGuard}.
 *
 * @param installments    pagamentos incluindo a entrada (1 = à vista)
 * @param concessionLevel nível pedido na escada (0 = sem concessão)
 * @param validityDays    validade do link de pagamento
 */
@org.springframework.modulith.NamedInterface("usecases")
public record OfferSpec(Integer installments, Integer concessionLevel, Integer validityDays) {

    public OfferSpec {
        installments = installments == null ? 1 : installments;
        concessionLevel = concessionLevel == null ? 0 : concessionLevel;
        validityDays = validityDays == null ? 3 : validityDays;
    }

    public OfferSpec validate() {
        Guard.require(installments >= 1 && installments <= 120, "cadence.offer_invalid", "Parcelas entre 1 e 120");
        Guard.require(concessionLevel >= 0 && concessionLevel <= 99, "cadence.offer_invalid", "Nível entre 0 e 99");
        Guard.require(validityDays >= 1 && validityDays <= 30, "cadence.offer_invalid", "Validade entre 1 e 30 dias");
        return this;
    }
}
