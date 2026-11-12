package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uso lido dos módulos de origem (views {@code costing.src_*}), já no dia de negócio ({@code TimeConfig.BUSINESS_ZONE}).
 * O id de usuário só aparece no tempo humano, para o cálculo em memória: nunca é gravado nos fatos.
 */
public final class Usage {

    private Usage() {
    }

    /** Tempo direto de uma pessoa num credor/carteira no dia (interações + pós-atendimento), em segundos. */
    public record DirectTime(LocalDate day, UUID userId, UUID creditorId, UUID portfolioId, long seconds) {
    }

    /** Tempo logado de uma pessoa no dia, em segundos. */
    public record LoggedTime(LocalDate day, UUID userId, long seconds) {
    }

    /**
     * Chamadas atendidas com a mesma duração de conversa num dia × credor × carteira × conta do provedor.
     *
     * @param ratePerMinute tarifa por minuto da conta (nula = conta sem tarifa)
     */
    public record CallUsage(LocalDate day, UUID creditorId, UUID portfolioId, UUID accountId, BigDecimal ratePerMinute,
                            int talkSeconds, long calls) {
    }

    /** Custo de IA (assistente ou Importação PDF) num dia × credor × carteira, na moeda informada pelo módulo. */
    public record AiUsage(LocalDate day, UUID creditorId, UUID portfolioId, String currency, BigDecimal amount) {
    }

    /** Mensagens saídas cobradas por mensagem (SMS, e-mail) num dia × credor × carteira. */
    public record MessageCount(LocalDate day, UUID creditorId, UUID portfolioId, TariffChannel channel, long messages) {
    }

    /** Envio de template de WhatsApp (início de conversa pela empresa), em ordem de conversa, categoria e horário. */
    public record TemplateSend(UUID conversationId, String providerCategory, Instant sentAt, LocalDate day,
                               UUID creditorId, UUID portfolioId) {
    }

    /** Valor em R$ já lançado por outro módulo (comissão variável, custas) num dia × credor × carteira. */
    public record Posted(LocalDate day, UUID creditorId, UUID portfolioId, BigDecimal amount, long entries) {
    }
}
