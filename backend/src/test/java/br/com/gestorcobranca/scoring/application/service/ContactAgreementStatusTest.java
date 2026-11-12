package br.com.gestorcobranca.scoring.application.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Situação final do desfecho por acordo na inteligência de contato: qual evento substitui a situação gravada. */
class ContactAgreementStatusTest {

    private static final Instant T = Instant.parse("2026-11-10T15:00:00.123456Z");

    @Test
    void laterEventReplacesAndEarlierDoesNot() {
        assertThat(ContactFactRecorder.supersedes(T.plusSeconds(1), "ACTIVE", T, "COMPLETED")).isTrue();
        assertThat(ContactFactRecorder.supersedes(T.minusSeconds(1), "COMPLETED", T, "ACTIVE")).isFalse();
    }

    @Test
    void atTheSameInstantTheFinalStatusWinsOverActiveInAnyOrder() {
        // IN_PAYMENT e SETTLED do mesmo pagamento, em qualquer ordem de chegada
        assertThat(ContactFactRecorder.supersedes(T, "COMPLETED", T, "ACTIVE")).isTrue();
        assertThat(ContactFactRecorder.supersedes(T, "ACTIVE", T, "COMPLETED")).isFalse();
        assertThat(ContactFactRecorder.supersedes(T, "BROKEN", T, "ACTIVE")).isTrue();
        assertThat(ContactFactRecorder.supersedes(T, "ACTIVE", T, "CANCELLED")).isFalse();
        // mesmo tipo de situação no mesmo instante: vale a que chegou depois
        assertThat(ContactFactRecorder.supersedes(T, "ACTIVE", T, "ACTIVE")).isTrue();
        assertThat(ContactFactRecorder.supersedes(T, "BROKEN", T, "COMPLETED")).isTrue();
    }

    @Test
    void instantsAreComparedAtTheDatabasePrecision() {
        // o banco guarda microssegundos: o instante gravado volta sem os nanossegundos do evento
        Instant event = T.plusNanos(789);
        assertThat(ContactFactRecorder.supersedes(event, "ACTIVE", T, "COMPLETED")).isFalse();
        assertThat(ContactFactRecorder.supersedes(event, "COMPLETED", T, "ACTIVE")).isTrue();
    }

    @Test
    void finalStatusMapping() {
        assertThat(ContactFactRecorder.finalStatus("IN_PAYMENT", "FORMALIZED")).isEqualTo("ACTIVE");
        assertThat(ContactFactRecorder.finalStatus("SETTLED", "IN_PAYMENT")).isEqualTo("COMPLETED");
        assertThat(ContactFactRecorder.finalStatus("RENEGOTIATED", "BROKEN")).isEqualTo("BROKEN");
        assertThat(ContactFactRecorder.finalStatus("RENEGOTIATED", "IN_PAYMENT")).isEqualTo("CANCELLED");
        assertThat(ContactFactRecorder.finalStatus("DRAFT", null)).isNull();
    }

    @Test
    void assistantInteractionsAreMirroredNotCounted() {
        assertThat(ContactFactRecorder.MIRRORED_SOURCES).contains("ASSISTANT", "TELEPHONY", "COMMUNICATION");
        assertThat(ContactFactRecorder.COUNTED_SOURCES).containsExactlyInAnyOrder("MANUAL", "SYSTEM");
    }
}
