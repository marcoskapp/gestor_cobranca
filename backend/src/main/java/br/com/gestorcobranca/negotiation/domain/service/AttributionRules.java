package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementOrigin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Origem e participantes do acordo na formalização (insumo da remuneração variável, ver
 * {@code docs/design/remuneracao-variavel.md} §3). Puro.
 * <table>
 *   <tr><th>Quem formaliza</th><th>Situação</th><th>Origem</th></tr>
 *   <tr><td>assistente</td><td>—</td><td>ASSISTANT_AUTONOMOUS</td></tr>
 *   <tr><td>humano</td><td>proposta ligada a um handoff do assistente</td>
 *       <td>CONFIRMED se as condições são as da sugestão; senão NEGOTIATED</td></tr>
 *   <tr><td>humano</td><td>proposta criada pelo assistente (dentro da alçada dele, sem poder formalizar)</td>
 *       <td>ASSISTANT_HANDOFF_CONFIRMED</td></tr>
 *   <tr><td>humano</td><td>proposta do humano marcada como feita com o copiloto</td><td>HUMAN_COPILOT</td></tr>
 *   <tr><td>humano</td><td>demais casos</td><td>HUMAN</td></tr>
 *   <tr><td>devedor (portal)</td><td>—</td><td>PORTAL_SELF_SERVICE</td></tr>
 *   <tr><td>sistema (régua)</td><td>—</td><td>CAMPAIGN</td></tr>
 * </table>
 * Participantes: {@code CLOSER} (quem formaliza), {@code ORIGINATOR} (quem criou a proposta),
 * {@code APPROVER} (quem aprovou acima da alçada) e {@code PRE_NEGOTIATOR} (assistente que conduziu
 * antes do handoff ou criou a proposta confirmada pelo humano).
 */
public final class AttributionRules {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";
    public static final String DEBTOR = "DEBTOR";
    public static final String SYSTEM = "SYSTEM";

    private AttributionRules() {
    }

    /**
     * @param handoffAssistantId política do assistente que fez a sugestão do handoff (nulo = sem handoff)
     * @param handoffTermsMatch  as condições formalizadas são as da sugestão (impressão digital igual)
     */
    public record Facts(String closerType, UUID closerId, String originatorType, UUID originatorId, boolean copilot,
                        UUID handoffAssistantId, boolean handoffTermsMatch, UUID approverId) {
    }

    public record Participant(String actorType, UUID actorId, String role) {
    }

    public record Attribution(AgreementOrigin origin, List<Participant> participants, UUID preNegotiatorId) {
    }

    public static Attribution resolve(Facts f) {
        AgreementOrigin origin;
        UUID preNegotiator = null;
        switch (f.closerType()) {
            case ASSISTANT -> origin = AgreementOrigin.ASSISTANT_AUTONOMOUS;
            case DEBTOR -> origin = AgreementOrigin.PORTAL_SELF_SERVICE;
            case SYSTEM -> origin = AgreementOrigin.CAMPAIGN;
            default -> {
                if (f.handoffAssistantId() != null) {
                    origin = f.handoffTermsMatch() ? AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED
                            : AgreementOrigin.ASSISTANT_HANDOFF_NEGOTIATED;
                    preNegotiator = f.handoffAssistantId();
                } else if (ASSISTANT.equals(f.originatorType())) {
                    origin = AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED;
                    preNegotiator = f.originatorId();
                } else {
                    origin = f.copilot() ? AgreementOrigin.HUMAN_COPILOT : AgreementOrigin.HUMAN;
                }
            }
        }
        List<Participant> participants = new ArrayList<>();
        participants.add(new Participant(f.closerType(), f.closerId(), "CLOSER"));
        participants.add(new Participant(f.originatorType(), f.originatorId(), "ORIGINATOR"));
        if (preNegotiator != null) {
            participants.add(new Participant(ASSISTANT, preNegotiator, "PRE_NEGOTIATOR"));
        }
        if (f.approverId() != null) {
            participants.add(new Participant(USER, f.approverId(), "APPROVER"));
        }
        return new Attribution(origin, List.copyOf(participants), preNegotiator);
    }
}
