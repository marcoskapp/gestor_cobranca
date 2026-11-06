package br.com.gestorcobranca.helpdesk.domain;

import br.com.gestorcobranca.helpdesk.domain.service.ManualText;
import br.com.gestorcobranca.helpdesk.domain.service.ManualText.Phrase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Marcadores de parâmetro do manual resolvidos com os valores da aplicação, com as mesmas regras do frontend. */
class ManualTextTest {

    private static final Map<String, Phrase> PHRASES = Map.of(
            "debt.cumulativeWindow", new Phrase("debt.cumulativeWindowDays", null, "no último dia", "nos últimos {n} dias",
                    "no período configurado"),
            "negotiation.grace", new Phrase("negotiation.graceDays", "no mesmo dia", "depois de 1 dia", "depois de {n} dias",
                    "depois da carência configurada"),
            "scoring.minAuc", new Phrase("scoring.minAuc", null, null, "padrão {n}", "definido nos parâmetros"),
            "settlement.dual", new Phrase("settlement.threshold", null, null, "acima de {n}", "acima do valor configurado"));
    private static final Map<String, String> FORMATS = Map.of("debt.cumulativeWindowDays", "integer",
            "negotiation.graceDays", "integer", "scoring.minAuc", "decimal", "settlement.threshold", "money");

    private static ManualText text(String window, String grace) {
        return new ManualText(PHRASES, FORMATS, Map.of("debt.cumulativeWindowDays", new BigDecimal(window),
                "negotiation.graceDays", new BigDecimal(grace), "scoring.minAuc", new BigDecimal("0.6"),
                "settlement.threshold", new BigDecimal("1234567.50")));
    }

    @Test
    void replacesEachMarkerWithThePhraseForTheValue() {
        ManualText t = text("30", "3");
        assertThat(t.resolve("Somando as reduções {{debt.cumulativeWindow}}; volta a valer {{negotiation.grace}}."))
                .isEqualTo("Somando as reduções nos últimos 30 dias; volta a valer depois de 3 dias.");
        assertThat(t.resolve("Mínimo ({{scoring.minAuc}}); ajustes {{settlement.dual}}."))
                .isEqualTo("Mínimo (padrão 0,60); ajustes acima de R$ 1.234.567,50.");
        assertThat(t.resolve("Sem marcador.")).isEqualTo("Sem marcador.");
        assertThat(t.resolve(null)).isNull();
    }

    @Test
    void singularAndZeroHaveTheirOwnText() {
        assertThat(text("1", "0").resolve("{{debt.cumulativeWindow}} / {{negotiation.grace}}"))
                .isEqualTo("no último dia / no mesmo dia");
        assertThat(text("30", "1").resolve("{{negotiation.grace}}")).isEqualTo("depois de 1 dia");
    }

    @Test
    void unknownMarkerOrParameterWithoutValuePreventsStartup() {
        assertThatThrownBy(() -> text("30", "3").resolve("{{debt.naoExiste}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("{{debt.naoExiste}}");
        assertThatThrownBy(() -> new ManualText(PHRASES, FORMATS, Map.of("debt.cumulativeWindowDays", BigDecimal.TEN)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("negotiation.graceDays")
                .hasMessageContaining("scoring.minAuc");
        assertThatThrownBy(() -> text("30", "3").resolve("{{ debt.cumulativeWindow }}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mal formado");
    }

    @Test
    void decimalShowsTheDigitsTheValueHasFromTwoToFourWithoutRounding() {
        Phrase p = new Phrase("x", null, null, "padrão {n}", "definido");
        assertThat(ManualTextTestAccess.render(p, "decimal", "0.625")).isEqualTo("padrão 0,625");
        assertThat(ManualTextTestAccess.render(p, "decimal", "0.6")).isEqualTo("padrão 0,60");
        assertThat(ManualTextTestAccess.render(p, "decimal", "0.615")).isEqualTo("padrão 0,615");
        assertThat(ManualTextTestAccess.render(p, "decimal", "0.6000")).isEqualTo("padrão 0,60");
        assertThat(ManualTextTestAccess.render(p, "decimal", "0.1234")).isEqualTo("padrão 0,1234");
        assertThat(ManualTextTestAccess.render(p, "decimal", "10")).isEqualTo("padrão 10,00");
        assertThatThrownBy(() -> ManualTextTestAccess.render(p, "decimal", "0.12345"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("4 casas");
        // reais continuam com duas casas
        assertThat(ManualTextTestAccess.render(p, "money", "1000")).isEqualTo("padrão R$ 1.000,00");
    }

    @Test
    void fingerprintDependsOnlyOnTheValuesUsed() {
        assertThat(text("30", "3").valuesFingerprint()).matches("[0-9a-f]{8}")
                .isEqualTo(text("30.0", "3").valuesFingerprint())
                .isNotEqualTo(text("31", "3").valuesFingerprint());
    }

    @Test
    void integerParameterMustBeWhole() {
        assertThatThrownBy(() -> text("1.5", "3")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inteiro");
        assertThat(text("30.00", "3").resolve("{{debt.cumulativeWindow}}")).isEqualTo("nos últimos 30 dias");
    }

    /** Acesso ao formatador (pacote do domínio) sem montar o catálogo inteiro. */
    private static final class ManualTextTestAccess {
        static String render(Phrase phrase, String format, String value) {
            return new ManualText(Map.of("x.y", phrase), Map.of("x", format), Map.of("x", new BigDecimal(value)))
                    .resolve("{{x.y}}");
        }
    }
}
