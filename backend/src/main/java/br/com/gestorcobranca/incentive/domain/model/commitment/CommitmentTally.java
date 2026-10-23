package br.com.gestorcobranca.incentive.domain.model.commitment;

/**
 * Contribuição aditiva de um compromisso (retorno ou promessa) para as colunas de
 * {@code performance_fact}. Mudanças de situação geram a diferença entre a contribuição nova e a
 * anterior, para que os fatos diários sejam sempre a soma das situações atuais.
 *
 * @param callbacksScheduled agendados: retornos não cancelados nem substituídos por reagendamento
 * @param callbacksKept      cumpridos até o fim da tolerância
 * @param callbacksMissed    atrasados (vencidos sem cumprimento, inclusive os cumpridos com atraso)
 * @param promisesRegistered promessas registradas (no dia do registro)
 * @param promisesDue        promessas vencidas: prazo encerrado com desfecho (cumpridas + quebradas)
 * @param promisesKept       promessas cumpridas
 * @param promisesBroken     promessas quebradas
 */
public record CommitmentTally(int callbacksScheduled, int callbacksKept, int callbacksMissed, int promisesRegistered,
                              int promisesDue, int promisesKept, int promisesBroken) {

    public static final CommitmentTally ZERO = new CommitmentTally(0, 0, 0, 0, 0, 0, 0);

    public static CommitmentTally callback(boolean scheduled, boolean kept, boolean missed) {
        return new CommitmentTally(scheduled ? 1 : 0, kept ? 1 : 0, missed ? 1 : 0, 0, 0, 0, 0);
    }

    public static CommitmentTally promiseRegistered() {
        return new CommitmentTally(0, 0, 0, 1, 0, 0, 0);
    }

    public static CommitmentTally promiseOutcome(boolean kept, boolean broken) {
        return new CommitmentTally(0, 0, 0, 0, kept || broken ? 1 : 0, kept ? 1 : 0, broken ? 1 : 0);
    }

    public CommitmentTally plus(CommitmentTally o) {
        return new CommitmentTally(callbacksScheduled + o.callbacksScheduled, callbacksKept + o.callbacksKept,
                callbacksMissed + o.callbacksMissed, promisesRegistered + o.promisesRegistered,
                promisesDue + o.promisesDue, promisesKept + o.promisesKept, promisesBroken + o.promisesBroken);
    }

    public CommitmentTally negate() {
        return new CommitmentTally(-callbacksScheduled, -callbacksKept, -callbacksMissed, -promisesRegistered,
                -promisesDue, -promisesKept, -promisesBroken);
    }

    public boolean isZero() {
        return equals(ZERO);
    }
}
