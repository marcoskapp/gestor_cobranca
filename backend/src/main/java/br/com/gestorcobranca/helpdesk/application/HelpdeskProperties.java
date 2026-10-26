package br.com.gestorcobranca.helpdesk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Parâmetros do chat de suporte ({@code app.helpdesk.*}). O provedor de IA e o GitHub têm blocos próprios
 * ({@code app.helpdesk.bedrock}, {@code app.helpdesk.sandbox}, {@code app.helpdesk.github}).
 *
 * @param limits        limites de uso (tokens, custo, taxa)
 * @param retentionDays conteúdo das conversas guardado (cifrado) por este prazo; depois é descartado e ficam só
 *                      metadados, consumo e avaliações
 * @param retentionCron descarte diário do conteúdo e expiração dos rascunhos de chamado
 * @param draftValidity rascunho de chamado não confirmado expira depois deste prazo
 * @param sandbox       modelo simulado determinístico (local/teste; recusado no perfil prod)
 */
@ConfigurationProperties(prefix = "app.helpdesk")
public record HelpdeskProperties(Limits limits, Integer retentionDays, String retentionCron, Duration draftValidity,
                                 Sandbox sandbox) {

    public HelpdeskProperties {
        limits = limits == null ? new Limits(null, null, null, null, null, null, null, null, null, null, null) : limits;
        retentionDays = retentionDays == null ? 90 : retentionDays;
        retentionCron = retentionCron == null ? "0 35 3 * * *" : retentionCron;
        draftValidity = draftValidity == null ? Duration.ofHours(24) : draftValidity;
        sandbox = sandbox == null ? new Sandbox(false, null, null, null) : sandbox;
        if (retentionDays < 1 || draftValidity.isNegative() || draftValidity.isZero()) {
            throw new IllegalArgumentException("Parâmetros inválidos em app.helpdesk");
        }
    }

    /**
     * @param maxOutputTokens          tokens de saída por chamada ao modelo
     * @param maxTokensPerTurn         tokens por pergunta (entrada com e sem cache + saída, todas as rodadas)
     * @param maxRounds                rodadas de ferramenta por pergunta
     * @param maxToolCalls             chamadas de ferramenta por pergunta
     * @param callTimeout              prazo de cada chamada ao modelo (inclusive a retentativa)
     * @param turnTimeout              prazo total da pergunta (abaixo do tempo ocioso do ALB, 60 s)
     * @param maxCostPerConversation   custo máximo de uma conversa (moeda do provedor)
     * @param maxCostPerUserPerDay     custo máximo por usuário no dia (fuso de operação)
     * @param maxQuestionsPerUserPerDay perguntas por usuário no dia
     * @param maxQuestionsPerMinute    perguntas por usuário por minuto (por instância)
     * @param maxTurnsPerConversation  perguntas por conversa (depois, "nova conversa")
     */
    public record Limits(Integer maxOutputTokens, Integer maxTokensPerTurn, Integer maxRounds, Integer maxToolCalls,
                         Duration callTimeout, Duration turnTimeout, BigDecimal maxCostPerConversation,
                         BigDecimal maxCostPerUserPerDay, Integer maxQuestionsPerUserPerDay,
                         Integer maxQuestionsPerMinute, Integer maxTurnsPerConversation) {

        public Limits {
            maxOutputTokens = maxOutputTokens == null ? 800 : maxOutputTokens;
            maxTokensPerTurn = maxTokensPerTurn == null ? 60_000 : maxTokensPerTurn;
            maxRounds = maxRounds == null ? 5 : maxRounds;
            maxToolCalls = maxToolCalls == null ? 8 : maxToolCalls;
            callTimeout = callTimeout == null ? Duration.ofSeconds(20) : callTimeout;
            turnTimeout = turnTimeout == null ? Duration.ofSeconds(45) : turnTimeout;
            maxCostPerConversation = maxCostPerConversation == null ? new BigDecimal("0.30") : maxCostPerConversation;
            maxCostPerUserPerDay = maxCostPerUserPerDay == null ? new BigDecimal("1.00") : maxCostPerUserPerDay;
            maxQuestionsPerUserPerDay = maxQuestionsPerUserPerDay == null ? 150 : maxQuestionsPerUserPerDay;
            maxQuestionsPerMinute = maxQuestionsPerMinute == null ? 8 : maxQuestionsPerMinute;
            maxTurnsPerConversation = maxTurnsPerConversation == null ? 30 : maxTurnsPerConversation;
            if (maxOutputTokens < 50 || maxTokensPerTurn < maxOutputTokens || maxRounds < 1 || maxToolCalls < 0
                    || callTimeout.isNegative() || callTimeout.isZero() || turnTimeout.compareTo(callTimeout) < 0
                    || maxCostPerConversation.signum() <= 0 || maxCostPerUserPerDay.signum() <= 0
                    || maxQuestionsPerUserPerDay < 1 || maxQuestionsPerMinute < 1 || maxTurnsPerConversation < 1) {
                throw new IllegalArgumentException("Limites inválidos em app.helpdesk.limits");
            }
        }
    }

    /**
     * @param costPer1kInput  custo simulado por mil tokens de entrada (exercita as métricas de custo)
     * @param costPer1kOutput custo simulado por mil tokens de saída
     * @param currency        moeda do custo simulado
     */
    public record Sandbox(boolean enabled, BigDecimal costPer1kInput, BigDecimal costPer1kOutput, String currency) {

        public Sandbox {
            costPer1kInput = costPer1kInput == null ? new BigDecimal("0.0001") : costPer1kInput;
            costPer1kOutput = costPer1kOutput == null ? new BigDecimal("0.0005") : costPer1kOutput;
            currency = currency == null ? "USD" : currency;
        }
    }
}
