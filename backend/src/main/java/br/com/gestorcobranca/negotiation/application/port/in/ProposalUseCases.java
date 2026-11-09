package br.com.gestorcobranca.negotiation.application.port.in;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ActorRef;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PolicyRef;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Propostas de acordo e aprovação (§5, F16). Humanos e o assistente usam os mesmos casos de uso:
 * toda condição passa pelo {@code NegotiationGuard} com o perfil de alçada do ator.
 * <ul>
 *   <li>{@link #create}: {@code NEGOTIATION_PROPOSE}; dentro da alçada → {@code DRAFT}; acima da alçada
 *       → {@code PENDING_APPROVAL} (humano) ou {@code HANDED_OFF} + {@code HandoffRequested} (contrato do
 *       communication, motivo {@code REQUIRES_APPROVAL}) (assistente); fora da política ou inelegível → 422 {@code proposal.conditions_rejected}.</li>
 *   <li>{@link #approve}/{@link #reject}: {@code NEGOTIATION_APPROVE}, perfil com {@code canApprove} e
 *       alçada suficiente para as condições, nunca o próprio proponente.</li>
 *   <li>{@link #accept}: {@code AGREEMENT_FORMALIZE}; registra a evidência do aceite do devedor e gera
 *       o termo (PDF com SHA-256).</li>
 *   <li>{@link #formalize}: {@code AGREEMENT_FORMALIZE}; cria o acordo (e, na renegociação, substitui o
 *       anterior).</li>
 * </ul>
 */
public interface ProposalUseCases {

    ProposalView create(CreateProposalCommand command);

    /**
     * O humano assume a sugestão do assistente transferida ({@code HANDED_OFF}): cria uma proposta
     * própria com as mesmas condições, revalidada com a sua alçada (aprovação se exceder).
     */
    ProposalView adopt(UUID handoffProposalId, String channel);

    ProposalView get(UUID proposalId);

    /** @param status DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, ACCEPTED, FORMALIZED, EXPIRED, HANDED_OFF */
    PageResponse<ProposalView> search(ProposalFilter filter, PageQuery page);

    /**
     * Fila de aprovação do ator: propostas {@code PENDING_APPROVAL} nos credores do escopo cujas
     * condições cabem na alçada do aprovador (perfil com {@code canApprove}), excluídas as próprias,
     * das mais antigas para as mais novas.
     */
    PageResponse<ProposalView> approvalQueue(ApprovalQueueFilter filter, PageQuery page);

    /**
     * Quanto a proposta passa da alçada de quem negociou e se cabe na de quem consulta (tela de aprovação).
     * {@code NEGOTIATION_APPROVE} (qualquer proposta visível no escopo) ou {@code NEGOTIATION_PROPOSE} (só a própria;
     * as de outros → 404); o limite de quem decide só vem para usuário com {@code NEGOTIATION_APPROVE}. A aprovação continua revalidando tudo no {@code NegotiationGuard}.
     */
    ApprovalAssessmentView approvalAssessment(UUID proposalId);

    ProposalView approve(UUID proposalId, String note);

    ProposalView reject(UUID proposalId, String reason);

    ProposalView accept(UUID proposalId, AcceptCommand command);

    AgreementUseCases.AgreementView formalize(UUID proposalId);

    /** Termo aceito (PDF). Registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    TermContent openTerm(UUID proposalId);

    /**
     * @param simulationId       simulação do próprio ator que originou a proposta (credor, devedor,
     *                           títulos, data-base, justificativa e canal)
     * @param level              nível da escada (até o nível pedido na simulação)
     * @param discounts          desconto por componente (%); nulo = limite do ator no nível e forma de pagamento
     * @param replacedAgreementId acordo substituído (renegociação; em vigor ou quebrado)
     * @param handoffProposalId  sugestão do assistente que originou a negociação humana (handoff)
     * @param copilot            o humano negociou com sugestões do assistente (modo copiloto)
     */
    record CreateProposalCommand(UUID simulationId, int level, ComponentsView discounts, PlanInput plan,
                                 UUID replacedAgreementId, UUID handoffProposalId, boolean copilot, String channel) {
    }

    /**
     * @param channel   canal em que o devedor aceitou (PHONE, WHATSAPP, EMAIL, SMS, CHAT, PORTAL, IN_PERSON)
     * @param reference identificador da mensagem, da sessão do portal ou da chamada (opcional)
     * @param clientIp  IP do devedor informado por integração (portal/comunicação); em chamadas HTTP o
     *                  servidor usa o IP da requisição
     * @param userAgent agente de usuário informado por integração (idem)
     */
    record AcceptCommand(String channel, String reference, String clientIp, String userAgent) {
    }

    /** @param caseId propostas de um caso de cobrança (aba Negociação da estação) */
    /** @param mine somente as propostas registradas pelo próprio usuário (ex.: "Minhas pendências") */
    record ProposalFilter(UUID creditorId, UUID debtorId, UUID caseId, String status, String actorType, boolean mine) {

        public ProposalFilter(UUID creditorId, UUID debtorId, UUID caseId, String status, String actorType) {
            this(creditorId, debtorId, caseId, status, actorType, false);
        }
    }

    /** @param creditorId só as propostas deste credor (nulo = todos os do escopo) */
    record ApprovalQueueFilter(UUID creditorId) {

        public static final ApprovalQueueFilter ALL = new ApprovalQueueFilter(null);
    }

    /**
     * @param dimension      LEVEL, DISCOUNT_SHARE (fração % do desconto máximo da política), INSTALLMENTS, AMOUNT (total
     *                       do acordo), DOWN_PAYMENT_SHARE (entrada em % do negociado, limite mínimo) ou RENEGOTIATIONS
     * @param proposerLimit  limite do perfil de quem negociou (nulo = sem limite próprio)
     * @param approverLimit  limite do perfil de quem consulta (nulo = não aprova ou sem limite próprio)
     * @param approverCovers o perfil de quem consulta cobre o pedido neste ponto
     */
    record AuthorityGapView(String dimension, BigDecimal requested, BigDecimal proposerLimit, BigDecimal approverLimit,
                            boolean approverCovers) {
    }

    /**
     * @param proposerProfileName nome do perfil de alçada de quem negociou (nulo se o perfil não existe mais: a tela
     *                            mostra só os motivos registrados em {@code authorityViolations})
     * @param approverProfileCode perfil de alçada de quem consulta (nulo sem {@code NEGOTIATION_APPROVE})
     * @param approverCanApprove  o perfil de quem consulta aprova propostas de outros
     * @param withinApproverAuthority as condições cabem nos limites do perfil de quem consulta
     * @param ownProposal         a proposta é de quem consulta (segregação: não pode decidir)
     * @param discountPercent     desconto em % da dívida atualizada
     */
    record ApprovalAssessmentView(UUID proposalId, String proposerProfileCode, String proposerProfileName,
                                  String approverProfileCode, String approverProfileName, boolean approverCanApprove,
                                  boolean withinApproverAuthority, boolean ownProposal, BigDecimal discountPercent,
                                  List<AuthorityGapView> gaps) {
    }

    record DecisionView(UUID decidedBy, String profileCode, Instant decidedAt, String note) {
    }

    /** Evidência do aceite (IP e agente de usuário ficam somente no banco). */
    record AcceptanceView(String channel, Instant acceptedAt, String actorType, UUID actorId, String reference,
                          String termSha256, Long termSize) {
    }

    record ProposalView(UUID id, String status, UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId,
                        UUID simulationId, PolicyRef policy, LocalDate referenceDate, ActorRef actor, int level,
                        String justificationReason, boolean copilot, String channel, List<UUID> titleIds,
                        ComponentsView discountRates, int installments, BigDecimal requestedDownPayment,
                        LocalDate firstDueDate, BigDecimal updatedAmount, BigDecimal discountAmount,
                        BigDecimal negotiatedAmount, BigDecimal downPayment, BigDecimal financingInterest,
                        BigDecimal totalAmount, BigDecimal envelopeUsage, LocalDate validUntil,
                        List<String> authorityViolations, UUID replacedAgreementId, int renegotiationNumber,
                        UUID handoffProposalId, DecisionView decision, AcceptanceView acceptance, UUID agreementId,
                        Instant formalizedAt, Instant createdAt, OfferView offer, long version) {
    }

    /** O chamador fecha o stream. */
    record TermContent(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
