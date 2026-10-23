package br.com.gestorcobranca.ingestion.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSituationTest {

    @ParameterizedTest
    @CsvSource({"ativo,ACTIVE", "Em Aberto,ACTIVE", "PAGO,PAID", "quitado,PAID", "Suspenso,SUSPENDED",
            "contestado,DISPUTED", "Em contestação,DISPUTED", "cancelado,CANCELLED", "BAIXADO,CANCELLED"})
    void parsesCreditorTerms(String raw, TitleSituation expected) {
        assertThat(TitleSituation.parse(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"P", "x", "em negociação", ""})
    void rejectsUnknownOrAmbiguousTerms(String raw) {
        assertThat(TitleSituation.parse(raw)).isEmpty();
    }
}
