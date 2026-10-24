package br.com.gestorcobranca.portal.application.port.in;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Autoatendimento do devedor autenticado (ator {@code DEBTOR}), sempre restrito ao próprio devedor e
 * às carteiras habilitadas do portal; exige o aviso de privacidade vigente reconhecido
 * ({@code portal.privacy_ack_required}, 409).
 * <p>
 * Negociação pelo negotiation com a alçada do canal (perfil {@code PORTAL}): o portal só oferece
 * condições válidas e dentro da alçada, e o servidor recusa qualquer outra no aceite
 * ({@code portal.offer_unavailable}). Aceite = proposta + aceite com termo (canal PORTAL, IP, agente,
 * instante, SHA-256) + formalização, numa transação; o Pix da 1ª parcela/à vista é gerado em seguida.
 */
public interface PortalSelfServiceUseCases {

    DebtsView debts();

    /** Ofertas pré-autorizadas (à vista e parcelado) para a carteira. Registra a simulação no negotiation. */
    OffersView offers(UUID portfolioId);

    /** Simulação com forma de pagamento escolhida pelo devedor. */
    OffersView simulate(UUID portfolioId, SimulateCommand command);

    AcceptanceResult accept(AcceptCommand command);

    List<AgreementSummary> agreements();

    AgreementDetail agreement(UUID agreementId);

    /** Pix da parcela (o aberto, se houver; senão um novo — 2ª via). */
    PixView pix(UUID agreementId, UUID installmentId);

    /** Termo do acordo (PDF). Registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    TermFile term(UUID agreementId);

    ServiceRequestView requestService(ServiceRequestCommand command);

    List<ServiceRequestView> serviceRequests();

    // ------------------------------------------------------------------ consulta

    record DebtsView(String creditorName, List<PortfolioDebt> portfolios) {
    }

    /**
     * @param updatedAmount   saldo atualizado na data de hoje pelo motor do negotiation (nulo se não negociável)
     * @param negotiable      há títulos elegíveis e nenhum acordo em vigor
     * @param unavailableReason AGREEMENT_ACTIVE, NOT_ELIGIBLE, NO_POLICY ou nulo
     */
    record PortfolioDebt(UUID portfolioId, String portfolioName, BigDecimal openBalance, BigDecimal updatedAmount,
                         boolean negotiable, String unavailableReason, UUID activeAgreementId, List<TitleSummary> titles) {
    }

    /** @param reference número do contrato/título mascarado (só os 4 últimos caracteres) */
    record TitleSummary(UUID titleId, String reference, LocalDate dueDate, long daysOverdue, BigDecimal openBalance,
                        String status) {
    }

    // ------------------------------------------------------------------ ofertas

    /**
     * @param firstDueDate 1º vencimento (nulo = hoje); até {@code maxDaysToFirstPayment} dias
     * @param downPayment  entrada (nula = mínima)
     */
    record SimulateCommand(int installments, BigDecimal downPayment, LocalDate firstDueDate) {
    }

    /**
     * @param offers               só as válidas e dentro da alçada do canal, a melhor por número de pagamentos
     * @param breakRule            "N parcelas em atraso há mais de D dias" desfazem o acordo
     */
    record OffersView(UUID simulationId, UUID portfolioId, BigDecimal updatedAmount, LocalDate referenceDate,
                      int maxInstallments, int maxDaysToFirstPayment, int proposalValidityDays,
                      Integer breakOverdueInstallments, Integer breakToleranceDays, List<OfferSummary> offers) {
    }

    /**
     * @param installments número de pagamentos (1 = à vista; inclui a entrada)
     * @param discount     desconto total sobre o saldo atualizado
     */
    record OfferSummary(int level, int installments, BigDecimal downPayment, BigDecimal installmentAmount,
                        BigDecimal total, BigDecimal discount, BigDecimal discountPercent, BigDecimal financingInterest,
                        LocalDate firstDueDate, List<ScheduleItem> schedule) {
    }

    record ScheduleItem(int number, LocalDate dueDate, BigDecimal amount) {
    }

    /** @param termsAccepted o devedor marcou "Li e aceito" (obrigatório) */
    record AcceptCommand(UUID simulationId, int level, int installments, BigDecimal downPayment, LocalDate firstDueDate,
                         boolean termsAccepted) {
    }

    /**
     * @param pix       Pix da 1ª parcela/à vista (nulo se não foi possível gerar agora; {@code pixError} explica)
     * @param pixError  código estável quando o Pix não foi gerado (o devedor pode pedir a 2ª via)
     */
    record AcceptanceResult(AgreementDetail agreement, PixView pix, String pixError) {
    }

    // ------------------------------------------------------------------ acordos e pagamento

    record AgreementSummary(UUID id, UUID portfolioId, String status, BigDecimal totalAmount, BigDecimal paidAmount,
                            int installments, LocalDate nextDueDate, BigDecimal nextAmount, Instant formalizedAt,
                            boolean closedInPortal) {
    }

    /** @param termSha256 hash do termo aceito (prova do conteúdo) */
    record AgreementDetail(UUID id, UUID portfolioId, String status, BigDecimal updatedAmount, BigDecimal discountAmount,
                           BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal remainingAmount,
                           Integer breakOverdueInstallments, Integer breakToleranceDays, Instant formalizedAt,
                           Instant acceptedAt, String termSha256, boolean termAvailable, List<InstallmentItem> installments) {
    }

    /** @param pixStatus situação do Pix aberto da parcela (PENDING, ACTIVE) ou nulo */
    record InstallmentItem(UUID id, int number, LocalDate dueDate, BigDecimal amount, BigDecimal paidAmount,
                           String status, String pixStatus) {
    }

    /**
     * @param status      PENDING (registrando no PSP), ACTIVE (pague com o QR ou copia e cola), PAID,
     *                    EXPIRED, CANCELLED ou FAILED
     * @param copyPaste   BR Code "copia e cola" (também é o conteúdo do QR Code)
     */
    record PixView(UUID instructionId, UUID agreementId, UUID installmentId, int installmentNumber, String status,
                   BigDecimal amount, Instant expiresAt, String copyPaste, Instant paidAt) {
    }

    record TermFile(String fileName, String contentType, long size, String sha256, InputStream content) {
    }

    // ------------------------------------------------------------------ atendimento humano

    /**
     * @param reason           TALK_TO_AGENT, DISPUTE, CANNOT_AFFORD ou OTHER
     * @param preferredChannel WHATSAPP, SMS ou EMAIL (por onde o atendente responde)
     * @param note             mensagem opcional (obrigatória em OTHER), até 500 caracteres
     */
    record ServiceRequestCommand(String reason, String preferredChannel, UUID portfolioId, String note) {
    }

    /** @param status OPEN, FORWARDED ou UNDELIVERED */
    record ServiceRequestView(UUID id, String protocol, String reason, String preferredChannel, String status,
                              Instant createdAt) {
    }
}
