package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.config.TimeConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendWindowTest {

    private final SendWindow morningsOnWeekdays = new SendWindow(LocalTime.of(9, 0), LocalTime.of(12, 0),
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

    @Test
    void insideTheWindowRunsNow() {
        Instant thursday10h = Instant.parse("2026-10-22T13:00:00Z"); // 10:00 em São Paulo
        assertThat(morningsOnWeekdays.contains(thursday10h, TimeConfig.BUSINESS_ZONE)).isTrue();
        assertThat(morningsOnWeekdays.nextOpening(thursday10h, TimeConfig.BUSINESS_ZONE)).isEqualTo(thursday10h);
    }

    @Test
    void afterTheWindowWaitsForTheNextAllowedDay() {
        Instant friday15h = Instant.parse("2026-10-23T18:00:00Z");
        assertThat(morningsOnWeekdays.nextOpening(friday15h, TimeConfig.BUSINESS_ZONE))
                .isEqualTo(Instant.parse("2026-10-26T12:00:00Z")); // segunda 09:00
        Instant thursday7h = Instant.parse("2026-10-22T10:00:00Z");
        assertThat(morningsOnWeekdays.nextOpening(thursday7h, TimeConfig.BUSINESS_ZONE))
                .isEqualTo(Instant.parse("2026-10-22T12:00:00Z"));
    }

    @Test
    void emptyWindowMeansAnyTimeAndOvernightIsRejected() {
        assertThat(SendWindow.always().contains(Instant.parse("2026-10-25T05:00:00Z"), TimeConfig.BUSINESS_ZONE)).isTrue();
        assertThatThrownBy(() -> new SendWindow(LocalTime.of(20, 0), LocalTime.of(2, 0), null).validate())
                .hasMessageContaining("meia-noite");
    }
}
