package br.com.gestorcobranca.telephony.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tempo sem sinal do navegador só em segundos inteiros (o manual fala em "por N segundos"). */
class TelephonyPresencePropertiesTest {

    private static TelephonyProperties withPresence(Duration presence) {
        return new TelephonyProperties(null, null, presence, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    void defaultIsNinetySecondsAndOnlyWholeSecondsAreAccepted() {
        assertThat(withPresence(null).presenceTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(withPresence(Duration.ofMinutes(2)).presenceTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThatThrownBy(() -> withPresence(Duration.ofMillis(1500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("segundos inteiros");
        assertThatThrownBy(() -> withPresence(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
