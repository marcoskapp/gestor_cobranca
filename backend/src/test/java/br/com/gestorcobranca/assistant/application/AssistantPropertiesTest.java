package br.com.gestorcobranca.assistant.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantPropertiesTest {

    private static AssistantProperties staleAfter(Duration staleTurnAfter) {
        return new AssistantProperties(null, null, staleTurnAfter, null, null, null, null);
    }

    @Test
    void staleTurnRecoveryWaitsLongerThanTheWorstTurn() {
        assertThat(staleAfter(null).staleTurnAfter()).isEqualTo(Duration.ofMinutes(20));
        assertThat(staleAfter(Duration.ofMinutes(18)).staleTurnAfter()).isEqualTo(Duration.ofMinutes(18));
        // 13 min de modelo no pior turno + 5 min de margem: abaixo disso a recuperação abandonaria um turno em andamento
        assertThatThrownBy(() -> staleAfter(Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale-turn-after");
        assertThatThrownBy(() -> staleAfter(Duration.ofMinutes(17).plusSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
