package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.tier.PersonSample;
import br.com.gestorcobranca.incentive.domain.model.tier.Tier;
import br.com.gestorcobranca.incentive.domain.model.tier.TierMatrix;
import br.com.gestorcobranca.incentive.domain.model.tier.TierPeriodicity;
import br.com.gestorcobranca.incentive.domain.model.tier.TierProposal;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Quartis por percentil, portão de qualidade, mínimos de amostra e matriz (distribuicao-por-desempenho.md §3). */
class TierClassifierTest {

    private static final TierSettings SETTINGS = TierSettings.defaults();

    @Test
    void quartisDeQuatroPessoasDistintas() {
        Map<String, Integer> q = TierClassifier.quartiles(values("a", "1", "b", "2", "c", "3", "d", "4"));

        assertThat(q).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c", 3).containsEntry("d", 4);
    }

    @Test
    void quartisDeOitoPessoasDivididasEmPares() {
        Map<String, Integer> q = TierClassifier.quartiles(values("a", "1", "b", "2", "c", "3", "d", "4", "e", "5",
                "f", "6", "g", "7", "h", "8"));

        assertThat(q.values().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                .containsEntry(1, 2L).containsEntry(2, 2L).containsEntry(3, 2L).containsEntry(4, 2L);
    }

    @Test
    void grupoMenorQueQuatroNaoForcaQuartilVazioNemCheio() {
        assertThat(TierClassifier.quartiles(values("só", "5"))).containsEntry("só", 3);
        assertThat(TierClassifier.quartiles(values("a", "1", "b", "2"))).containsEntry("a", 2).containsEntry("b", 4);
        assertThat(TierClassifier.quartiles(values("a", "1", "b", "2", "c", "3")))
                .containsEntry("a", 1).containsEntry("b", 3).containsEntry("c", 4);
    }

    @Test
    void empatadosFicamNoMesmoQuartil() {
        Map<String, Integer> q = TierClassifier.quartiles(values("a", "8", "b", "8", "c", "8", "d", "9"));

        assertThat(q.get("a")).isEqualTo(q.get("b")).isEqualTo(q.get("c")).isEqualTo(2);
        assertThat(q).containsEntry("d", 4);
        assertThat(TierClassifier.quartiles(values("a", "7", "b", "7", "c", "7", "d", "7")).values()).containsOnly(3);
    }

    @Test
    void matrizPadraoCombinaQualidadeEEficiencia() {
        // qualidade: a < b < c < d; eficiência: inversa (d pior)
        List<PersonSample> samples = List.of(
                sample("a", 10, "6.0", false, 40, "100", "130"),
                sample("b", 10, "8.0", false, 40, "100", "120"),
                sample("c", 10, "9.0", false, 40, "100", "110"),
                sample("d", 10, "9.5", false, 40, "100", "100"));

        Map<UUID, TierProposal> result = byUser(TierClassifier.classify(samples, SETTINGS));

        // a: qualidade Q1 (abaixo do portão também), eficiência Q4 → C
        assertThat(result.get(id("a"))).extracting(TierProposal::qualityQuartile, TierProposal::efficiencyQuartile,
                TierProposal::proposedTier).containsExactly(1, 4, Tier.C);
        assertThat(result.get(id("a")).qualityGateFailed()).isTrue();
        // b: Q2 × Q3 → C; c: Q3 × Q2 → B; d: Q4 × Q1 → C
        assertThat(result.get(id("b")).proposedTier()).isEqualTo(Tier.C);
        assertThat(result.get(id("c")).proposedTier()).isEqualTo(Tier.B);
        assertThat(result.get(id("d")).proposedTier()).isEqualTo(Tier.C);
        assertThat(result.get(id("d")).recoveryRatio()).isEqualByComparingTo("1.0000");
    }

    @Test
    void falhaCriticaOuNotaAbaixoDoPortaoLimitaAQualidadeAQ2() {
        List<PersonSample> samples = List.of(
                sample("a", 10, "7.5", false, 40, "100", "50"),
                sample("b", 10, "8.0", false, 40, "100", "60"),
                sample("c", 10, "9.0", false, 40, "100", "70"),
                sample("d", 10, "9.8", true, 40, "100", "200"));

        TierProposal d = byUser(TierClassifier.classify(samples, SETTINGS)).get(id("d"));

        assertThat(d.qualityGateFailed()).isTrue();
        assertThat(d.qualityQuartile()).isEqualTo(2);
        // Q2 × Q4 → B (sem o portão seria Q4 × Q4 → A)
        assertThat(d.proposedTier()).isEqualTo(Tier.B);

        TierProposal c = byUser(TierClassifier.classify(List.of(sample("c", 10, "6.9", false, 40, "100", "70")),
                SETTINGS)).get(id("c"));
        assertThat(c.qualityQuartile()).isEqualTo(2);
        assertThat(c.qualityGateFailed()).isTrue();
    }

    @Test
    void semAmostraSuficienteValeONivelPadrao() {
        List<PersonSample> samples = List.of(
                sample("poucas-avaliacoes", 4, "10", false, 40, "100", "200"),
                sample("poucos-casos", 10, "10", false, 29, "100", "200"),
                sample("sem-esperado", 10, "10", false, 40, "0", "0"),
                PersonSample.empty(id("novo")));
        TierSettings settings = new TierSettings(90, 5, 30, new BigDecimal("7.00"), TierMatrix.standard(), Tier.C,
                TierPeriodicity.MONTHLY, 3);

        Map<UUID, TierProposal> result = byUser(TierClassifier.classify(samples, settings));

        assertThat(result.get(id("poucas-avaliacoes")).missingQuality()).isTrue();
        assertThat(result.get(id("poucos-casos")).missingEfficiency()).isTrue();
        assertThat(result.get(id("sem-esperado")).missingEfficiency()).isTrue();
        assertThat(result.get(id("sem-esperado")).recoveryRatio()).isNull();
        assertThat(result.get(id("novo")).missingSample()).isTrue();
        assertThat(result.values()).extracting(TierProposal::proposedTier).containsOnly(Tier.C);
    }

    @Test
    void quemNaoTemAmostraNaoEntraNoRankingDosDemais() {
        // só "a" e "b" têm casos suficientes: a eficiência é a posição entre os dois
        List<PersonSample> samples = List.of(
                sample("a", 10, "9", false, 40, "100", "90"),
                sample("b", 10, "9", false, 40, "100", "110"),
                sample("c", 10, "9", false, 5, "100", "500"));

        Map<UUID, TierProposal> result = byUser(TierClassifier.classify(samples, SETTINGS));

        assertThat(result.get(id("a")).efficiencyQuartile()).isEqualTo(2);
        assertThat(result.get(id("b")).efficiencyQuartile()).isEqualTo(4);
        assertThat(result.get(id("c")).efficiencyQuartile()).isNull();
        // a explicação guarda o índice mesmo sem amostra mínima
        assertThat(result.get(id("c")).recoveryRatio()).isEqualByComparingTo("5.0000");
    }

    @Test
    void agendaDevidaNoPeriodoMensalAPartirDoPrimeiroDiaUtilESemanalAPartirDaSegunda() {
        LocalDate firstBusiness = LocalDate.of(2026, 12, 1);
        assertThat(TierSchedule.periodStart(TierPeriodicity.MONTHLY, LocalDate.of(2026, 12, 15), firstBusiness))
                .isEqualTo(firstBusiness);
        assertThat(TierSchedule.isDue(TierPeriodicity.MONTHLY, firstBusiness, firstBusiness)).isTrue();
        // rotina que não rodou no 1º dia útil: ainda devida nos dias seguintes do mês
        assertThat(TierSchedule.isDue(TierPeriodicity.MONTHLY, LocalDate.of(2026, 12, 2), firstBusiness)).isTrue();
        // antes do 1º dia útil (1º de janeiro, feriado; 1º dia útil 04/01/2027): ainda não
        assertThat(TierSchedule.isDue(TierPeriodicity.MONTHLY, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 4)))
                .isFalse();
        // semanal: o período começa na segunda (mesmo de quarta ou de domingo)
        assertThat(TierSchedule.periodStart(TierPeriodicity.WEEKLY, LocalDate.of(2026, 11, 16), firstBusiness))
                .isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(TierSchedule.periodStart(TierPeriodicity.WEEKLY, LocalDate.of(2026, 11, 18), firstBusiness))
                .isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(TierSchedule.periodStart(TierPeriodicity.WEEKLY, LocalDate.of(2026, 11, 22), firstBusiness))
                .isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(TierSchedule.isDue(TierPeriodicity.WEEKLY, LocalDate.of(2026, 11, 17), firstBusiness)).isTrue();
    }

    // ------------------------------------------------------------------ auxiliares

    private static Map<String, BigDecimal> values(String... pairs) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], new BigDecimal(pairs[i + 1]));
        }
        return map;
    }

    private static PersonSample sample(String key, long evaluations, String average, boolean critical, long cases,
                                       String expected, String realized) {
        return new PersonSample(id(key), evaluations, new BigDecimal(average), critical, cases, new BigDecimal(expected),
                new BigDecimal(realized));
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes());
    }

    private static Map<UUID, TierProposal> byUser(List<TierProposal> proposals) {
        return proposals.stream().collect(Collectors.toMap(TierProposal::userId, Function.identity()));
    }
}
