package br.com.gestorcobranca.telephony.domain.service;

import br.com.gestorcobranca.telephony.domain.model.CallResult;
import br.com.gestorcobranca.telephony.domain.model.DialerEntryStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Retentativas da campanha de discagem: atendida e conectada encerra o item; os demais resultados
 * (não atende, ocupado, caixa postal, abandono, falha, cancelamento) voltam à lista após o
 * intervalo da campanha, até o máximo de tentativas. Ocupado é tentado de novo em metade do
 * intervalo (mínimo 5 min), padrão comum de discadores. A frequência por devedor continua
 * limitada pelo porteiro de contato (limites diário/semanal e intervalo mínimo).
 */
public final class RetryPolicy {

    private static final Duration MIN_BUSY_RETRY = Duration.ofMinutes(5);

    private RetryPolicy() {
    }

    public record Decision(DialerEntryStatus status, Instant nextAttemptAt) {
    }

    public static Decision after(CallResult result, int attemptsMade, int maxAttempts, Duration retryInterval,
                                 Instant now) {
        if (result == CallResult.ANSWERED) {
            return new Decision(DialerEntryStatus.COMPLETED, null);
        }
        if (attemptsMade >= maxAttempts) {
            return new Decision(DialerEntryStatus.EXHAUSTED, null);
        }
        Duration wait = result == CallResult.BUSY ? max(retryInterval.dividedBy(2), MIN_BUSY_RETRY) : retryInterval;
        return new Decision(DialerEntryStatus.PENDING, now.plus(wait));
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
