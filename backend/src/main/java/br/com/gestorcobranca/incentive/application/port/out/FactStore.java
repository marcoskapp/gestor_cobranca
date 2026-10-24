package br.com.gestorcobranca.incentive.application.port.out;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitment;
import br.com.gestorcobranca.incentive.domain.model.commitment.CommitmentTally;
import br.com.gestorcobranca.incentive.domain.model.commitment.PromiseOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatos operacionais materializados a partir de eventos (tabelas de fatos do módulo). Escrita
 * incremental (os consumidores são idempotentes) e leituras de apoio à atribuição e à apuração.
 */
public interface FactStore {

    /** Dimensões de uma linha de {@code performance_fact}. */
    record FactKey(LocalDate date, UUID userId, UUID creditorId, UUID portfolioId, DelayBucket delayBucket, String channel) {
    }

    /** Incrementos aditivos (zero onde não se aplica). */
    record Measures(int attempts, int effectiveContacts, int answeredCalls, long talkSeconds, int agreements,
                    BigDecimal negotiatedAmount, BigDecimal agreementAmount, BigDecimal updatedBalance,
                    BigDecimal discountAmount, BigDecimal downPaymentAmount, BigDecimal envelopeUsageSum,
                    int envelopeCount, int concessionLevelSum, int concessionCount, int firstInstallments,
                    BigDecimal accruedAmount, BigDecimal reversedAmount) {

        public static Measures interaction(boolean attempt, boolean effectiveContact) {
            return new Measures(attempt ? 1 : 0, effectiveContact ? 1 : 0, 0, 0, 0, null, null, null, null, null, null,
                    0, 0, 0, 0, null, null);
        }

        public static Measures call(long talkSeconds) {
            return new Measures(0, 0, 1, talkSeconds, 0, null, null, null, null, null, null, 0, 0, 0, 0, null, null);
        }

        public static Measures firstInstallment() {
            return new Measures(0, 0, 0, 0, 0, null, null, null, null, null, null, 0, 0, 0, 1, null, null);
        }

        public static Measures recovery(BigDecimal accrued, BigDecimal reversed) {
            return new Measures(0, 0, 0, 0, 0, null, null, null, null, null, null, 0, 0, 0, 0, accrued, reversed);
        }
    }

    void increment(FactKey key, Measures measures);

    void recordWorkedCase(LocalDate date, UUID userId, UUID caseId, UUID creditorId, UUID portfolioId, String channel);

    void recordEffectiveContact(UUID interactionId, UUID caseId, UUID operatorId, Instant occurredAt);

    /** Último humano com contato efetivo no caso entre os instantes (janela de influência). */
    Optional<UUID> lastEffectiveContact(UUID caseId, Instant from, Instant to);

    /** Abre espera de resposta numa conversa (mantém a mais antiga, se já houver). */
    void openConversationWait(UUID conversationId, Instant since);

    /** Fecha a espera e devolve desde quando o devedor aguardava, se havia espera aberta. */
    Optional<Instant> closeConversationWait(UUID conversationId);

    void recordResponseSample(UUID messageId, LocalDate date, UUID userId, UUID creditorId, UUID portfolioId,
                              String channel, long seconds);

    /** Substitui o tempo logado dos usuários nos dias informados. */
    void replaceLoggedTime(LocalDate from, LocalDate to, Collection<UUID> userIds, Map<UUID, Map<LocalDate, Long>> seconds);

    /** Dias com tempo logado positivo por usuário no intervalo. */
    Map<UUID, Integer> workedDays(Collection<UUID> userIds, LocalDate from, LocalDate to);

    /** Cria o período de apuração (aberto) se ainda não existir; seguro sob concorrência. */
    void ensurePeriod(LocalDate start, LocalDate end);

    // ---------------------------------------------------------------- casos

    record CaseSnapshot(UUID sourceEventId, UUID caseId, Instant effectiveAt, UUID creditorId, UUID portfolioId,
                        UUID assigneeId, UUID teamId, String status, Boolean eligible, BigDecimal eligibleBalance) {
    }

    /**
     * Registra o retrato do caso. Status, elegibilidade, equipe e saldo não informados (null) são
     * herdados do retrato anterior; o responsável é sempre o informado (null = na fila).
     */
    void recordCaseSnapshot(CaseSnapshot snapshot);

    /** Responsável pelo caso no instante (modelo de crédito por carteira). */
    Optional<UUID> assigneeAt(UUID caseId, Instant at);

    // ---------------------------------------------------------------- recuperado × esperado (scoring)

    /**
     * Safra de score com recuperado × esperado (evento {@code ExpectedRecoveryMeasured}).
     *
     * @param userId responsável no momento do cálculo (null = fila/assistente)
     */
    record ExpectedRecovery(UUID sourceEventId, UUID cohortId, LocalDate referenceDate, int outcomeDays, UUID creditorId,
                            UUID portfolioId, UUID userId, long cases, BigDecimal expectedAmount,
                            BigDecimal realizedAmount, UUID modelId, int modelVersion, Instant updatedAt) {
    }

    /** Insere ou substitui a safra (idempotente por {@code cohortId}; vale o evento mais recente). */
    void saveExpectedRecovery(ExpectedRecovery recovery);

    // ---------------------------------------------------------------- retornos e promessas

    /** Soma a contribuição (positiva ou compensatória) de compromissos às colunas de retornos e promessas. */
    void incrementCommitments(FactKey key, CommitmentTally tally);

    /** Compromissos conhecidos de uma tarefa de retorno (um por horário combinado). */
    List<CallbackCommitment> callbackCommitments(UUID taskId);

    /** Grava (insere ou substitui) a situação do compromisso de retorno. */
    void saveCallbackCommitment(CallbackCommitment commitment);

    Optional<PromiseOutcome> promiseOutcome(UUID promiseId);

    /** Grava (insere ou substitui) a situação da promessa. */
    void savePromiseOutcome(PromiseOutcome outcome);

    // ---------------------------------------------------------------- acordos

    void agreementStatus(UUID agreementId, String status, Instant at, BigDecimal totalAmount, UUID replacedAgreementId);

    void agreementFormalized(UUID agreementId, Instant formalizedAt, BigDecimal totalAmount, UUID replacedAgreementId);

    /** @return true se a 1ª parcela passou a constar como paga agora (não constava antes) */
    boolean firstInstallmentPaid(UUID agreementId, Instant paidAt);

    void firstInstallmentReverted(UUID agreementId);

    Optional<Instant> firstInstallmentPaidAt(UUID agreementId);

    /** Safra para o deflator: acordos formalizados pelo closer no intervalo e quantos quebraram dentro da janela. */
    record Safra(int agreements, int earlyBreaks) {
    }

    Safra safra(UUID closerId, Instant formalizedFrom, Instant formalizedTo, int windowDays);

    /** Uso médio do envelope e amostra dos acordos formalizados pelo closer no intervalo. */
    record EnvelopeUsage(int sample, BigDecimal average) {
    }

    EnvelopeUsage envelopeUsage(UUID closerId, Instant formalizedFrom, Instant formalizedTo);

    /** Soma das frações de crédito (peso × fator) dos acordos do beneficiário com 1ª parcela paga no intervalo. */
    BigDecimal firstInstallmentCreditWeight(UUID userId, Instant from, Instant to);

    /** Saldo elegível dos casos sob responsabilidade dos usuários no instante (denominador da taxa de recuperação). */
    BigDecimal eligibleBalanceAt(Collection<UUID> userIds, Instant at, Collection<UUID> creditorIds);
}
