package br.com.gestorcobranca.campaign.application.port.out;

import br.com.gestorcobranca.campaign.domain.model.OfferSpec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Oferta e link de pagamento para uma etapa de régua (ação {@code OFFER}). Contrato neutro: o
 * adaptador deve calcular a oferta pelo motor do negotiation com a alçada própria da campanha
 * (passando pelo {@code NegotiationGuard}) e gerar a instrução/link no módulo payment.
 * <p>
 * <b>Sem adaptador nesta versão</b>: o negotiation ainda não tem sujeito de alçada para campanhas
 * ({@code authority.actor_not_supported} para atores de sistema) e o módulo payment não existe.
 * Sem bean desta porta, a publicação de régua com etapa de oferta é recusada
 * ({@code 409 cadence.offer_unavailable}) e, por defesa, a etapa é pulada com {@code OFFER_UNAVAILABLE}.
 */
public interface CampaignOfferPort {

    /**
     * @param idempotencyKey chave da execução da etapa (a mesma oferta em reprocessamentos)
     */
    OfferResult createOffer(OfferRequest request);

    record OfferRequest(UUID enrollmentId, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId,
                        OfferSpec spec, String channel, String idempotencyKey) {
    }

    /**
     * @param available  oferta gerada; senão {@code reason} explica (ex.: fora da política)
     * @param referenceId proposta/instrução gerada (atribuição do acordo à campanha)
     * @param paymentUrl link de pagamento (https)
     */
    record OfferResult(boolean available, String reason, UUID referenceId, BigDecimal amount, LocalDate dueDate,
                       String paymentUrl) {
    }
}
