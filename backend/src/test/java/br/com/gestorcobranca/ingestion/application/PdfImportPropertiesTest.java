package br.com.gestorcobranca.ingestion.application;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tamanho máximo do PDF só em MB inteiros (a tela e o manual falam em "até N MB"). */
class PdfImportPropertiesTest {

    private static PdfImportProperties withMaxSize(DataSize size) {
        return new PdfImportProperties(size, 200, 30, 5, 150, 110, 0.85, 220, Duration.ofSeconds(90), 6000, 12000, 1,
                Duration.ofDays(120), 200);
    }

    @Test
    void defaultIsTwentyMegabytesAndOnlyWholeMegabytesAreAccepted() {
        assertThat(PdfImportProperties.defaults().maxFileSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(withMaxSize(DataSize.ofMegabytes(30)).maxFileSize().toMegabytes()).isEqualTo(30);
        assertThatThrownBy(() -> withMaxSize(DataSize.ofKilobytes(20500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MB inteiros");
        assertThatThrownBy(() -> withMaxSize(DataSize.ofBytes(0))).isInstanceOf(IllegalArgumentException.class);
    }
}
