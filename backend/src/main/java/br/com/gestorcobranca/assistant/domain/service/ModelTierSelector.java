package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.ModelRouting;
import br.com.gestorcobranca.assistant.domain.model.ModelTier;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;

import java.util.Set;

/**
 * Nível de modelo de cada chamada do turno (política {@link ModelRouting}). Com {@code BY_STAGE}, a chamada
 * vai ao nível {@code ADVANCED} quando a conversa está numa etapa em que erro custa dinheiro ou gera problema
 * de compliance:
 * <ul>
 *   <li>a intenção registrada no turno (ou, antes de o modelo registrá-la, a do turno anterior) é negociar,
 *       aceitar ou pagar ({@link #ADVANCED_INTENTS});</li>
 *   <li>a etapa já tem ofertas simuladas, proposta ou parcelas de acordo devolvidas pelo motor (a negociação
 *       começou: o resto da etapa segue no nível avançado, até a conversa voltar ao assistente numa nova
 *       etapa, que zera a memória).</li>
 * </ul>
 * O resto (saudação, identificação, saldo, dúvidas, promessa, retorno) fica em {@code STANDARD}. Função
 * pura: o servidor decide; o modelo não escolhe o próprio nível.
 */
public final class ModelTierSelector {

    /** intenções do catálogo de {@code register_intent} que exigem o nível avançado */
    public static final Set<String> ADVANCED_INTENTS = Set.of("NEGOTIATE", "ACCEPT", "PAYMENT");

    /** Motivo da escolha (tag de métrica, baixa cardinalidade). */
    public enum Reason { POLICY, INTENT, STAGE, DEFAULT }

    public record Selection(ModelTier tier, Reason reason) {
    }

    private ModelTierSelector() {
    }

    /**
     * @param turnIntent intenção registrada pelo modelo neste turno (nula antes de {@code register_intent})
     * @param memory     memória da etapa (escrita só pelo servidor)
     */
    public static Selection select(PolicySettings policy, String turnIntent, SessionMemory memory) {
        if (policy != null && policy.routing() == ModelRouting.ADVANCED_ONLY) {
            return new Selection(ModelTier.ADVANCED, Reason.POLICY);
        }
        SessionMemory m = memory == null ? SessionMemory.EMPTY : memory;
        String intent = turnIntent != null ? turnIntent : m.lastIntent();
        if (intent != null && ADVANCED_INTENTS.contains(intent)) {
            return new Selection(ModelTier.ADVANCED, Reason.INTENT);
        }
        if (!m.offers().isEmpty() || m.proposal() != null || !m.installments().isEmpty()) {
            return new Selection(ModelTier.ADVANCED, Reason.STAGE);
        }
        return new Selection(ModelTier.STANDARD, Reason.DEFAULT);
    }
}
