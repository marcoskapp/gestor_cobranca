package br.com.gestorcobranca.assistant.application;

import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Parâmetros do módulo assistant ({@code app.assistant.*}). Os limites de cada conversa (tokens,
 * custo, tempo, turnos) ficam na política do assistente, não aqui.
 *
 * @param historyMessages   mensagens mais recentes da conversa enviadas ao modelo
 * @param turnMaxAttempts   tentativas do job de turno (falha de banco/infra; erros do modelo não repetem)
 * @param staleTurnAfter    turno sem conclusão além deste prazo é recuperado (instância caiu); antes dele o turno
 *                          está em andamento e outro job da conversa é adiado. Precisa superar o pior turno
 *                          ({@link PolicySettings.Limits#worstTurnModelTime()} + {@link #STALE_TURN_MARGIN})
 * @param earlyBreakDays    janela da "quebra precoce" nas métricas de qualidade
 * @param retentionCron     descarte diário do conteúdo da trilha (política MESSAGE_CONTENT do compliance)
 * @param retentionBatch    turnos por lote e assessoria
 * @param sandbox           modelo simulado determinístico (local/teste; recusado no perfil prod)
 */
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(Integer historyMessages, Integer turnMaxAttempts, Duration staleTurnAfter,
                                  Integer earlyBreakDays, String retentionCron, Integer retentionBatch,
                                  Sandbox sandbox) {

    /** Folga sobre o tempo de modelo do pior turno: ferramentas, envios e gravações do turno. */
    public static final Duration STALE_TURN_MARGIN = Duration.ofMinutes(5);

    public AssistantProperties {
        historyMessages = historyMessages == null ? 12 : historyMessages;
        turnMaxAttempts = turnMaxAttempts == null ? 3 : turnMaxAttempts;
        staleTurnAfter = staleTurnAfter == null ? Duration.ofMinutes(20) : staleTurnAfter;
        Duration minimum = PolicySettings.Limits.worstTurnModelTime().plus(STALE_TURN_MARGIN);
        if (staleTurnAfter.compareTo(minimum) < 0) {
            // o turno não segura lock: recuperar antes do pior turno abandonaria um turno ainda em andamento
            throw new IllegalArgumentException("app.assistant.stale-turn-after (" + staleTurnAfter + ") precisa ser de "
                    + "pelo menos " + minimum + ": pior turno com o modelo (" + PolicySettings.Limits.worstTurnModelTime()
                    + ") mais a margem para ferramentas e envios (" + STALE_TURN_MARGIN + ")");
        }
        earlyBreakDays = earlyBreakDays == null ? 60 : earlyBreakDays;
        retentionCron = retentionCron == null ? "0 55 2 * * *" : retentionCron;
        retentionBatch = retentionBatch == null ? 500 : retentionBatch;
        sandbox = sandbox == null ? new Sandbox(false, null, null, null) : sandbox;
    }

    /**
     * @param costPer1kInput  custo simulado por mil tokens de entrada (exercita as métricas de custo)
     * @param costPer1kOutput custo simulado por mil tokens de saída
     * @param currency        moeda do custo simulado
     */
    public record Sandbox(boolean enabled, BigDecimal costPer1kInput, BigDecimal costPer1kOutput, String currency) {

        public Sandbox {
            costPer1kInput = costPer1kInput == null ? new BigDecimal("0.003") : costPer1kInput;
            costPer1kOutput = costPer1kOutput == null ? new BigDecimal("0.015") : costPer1kOutput;
            currency = currency == null ? "USD" : currency;
        }
    }
}
