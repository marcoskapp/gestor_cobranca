package br.com.gestorcobranca.analytics.domain.service;

import br.com.gestorcobranca.analytics.domain.model.CreditorPeriod;
import br.com.gestorcobranca.analytics.domain.model.Ratio;
import br.com.gestorcobranca.analytics.domain.service.CreditorIndicatorRules.PortfolioShare;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Indicadores do portal do credor (decisões do responsável de 26/11/2026): acordo em dia, alerta, carteiras e período. */
class CreditorIndicatorRulesTest {

    @Test
    void acordoEmDiaSemParcelaVencidaOuComAQuebraSuspensa() {
        assertThat(CreditorIndicatorRules.onTrack(false, false)).isTrue();
        assertThat(CreditorIndicatorRules.onTrack(true, false)).isFalse();
        // pagamento informado em análise: a quebra está suspensa e o acordo conta como em dia
        assertThat(CreditorIndicatorRules.onTrack(true, true)).isTrue();
        assertThat(CreditorIndicatorRules.onTrack(false, true)).isTrue();
    }

    @Test
    void alertaAbaixoDe80PorCentoESemAcordoNaoHaIndicador() {
        // 4 em dia de 5 = 80%: no limite, sem alerta; 3 de 4 = 75%: alerta
        assertThat(CreditorIndicatorRules.onTrackAlert(CreditorIndicatorRules.onTrackRatio(4, 5))).isFalse();
        Ratio low = CreditorIndicatorRules.onTrackRatio(3, 4);
        assertThat(low.value()).isEqualByComparingTo("0.75");
        assertThat(CreditorIndicatorRules.onTrackAlert(low)).isTrue();
        // sem acordo ativo: indisponível (nunca 0% nem 100%) e sem alerta
        Ratio none = CreditorIndicatorRules.onTrackRatio(0, 0);
        assertThat(none.value()).isNull();
        assertThat(none.unavailableReason()).isEqualTo("no_denominator");
        assertThat(CreditorIndicatorRules.onTrackAlert(none)).isFalse();
    }

    @Test
    void cincoCarteirasComMaisRecuperadoEOutrosComAsDemaisESemCarteira() {
        List<PortfolioShare> shares = List.of(share("A", "100.00"), share("B", "600.00"), share("C", "300.00"),
                share("D", "500.00"), share("E", "200.00"), share("F", "400.00"), share("G", "50.00"),
                share("Z", "0.00"), new PortfolioShare(null, null, new BigDecimal("25.00")));
        List<PortfolioShare> top = CreditorIndicatorRules.topWithOthers(shares);
        assertThat(top).extracting(PortfolioShare::name).containsExactly("B", "D", "F", "C", "E", null);
        // Outros = A (100) + G (50) + sem carteira (25); a carteira sem recuperado fica fora
        assertThat(top.getLast().portfolioId()).isNull();
        assertThat(top.getLast().recovered()).isEqualByComparingTo("175.00");
        // até 5 carteiras e nada a somar: sem "Outros"
        assertThat(CreditorIndicatorRules.topWithOthers(List.of(share("A", "10.00"), share("B", "20.00"))))
                .extracting(PortfolioShare::name).containsExactly("B", "A");
        assertThat(CreditorIndicatorRules.topWithOthers(List.of())).isEmpty();
    }

    @Test
    void periodosFechadosEOPeriodoAnteriorDeMesmaDuracao() {
        LocalDate today = LocalDate.of(2026, 11, 26);
        assertThat(CreditorPeriod.THIS_MONTH.range(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 11, 1), today));
        assertThat(CreditorPeriod.THIS_MONTH.previous(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 26)));
        assertThat(CreditorPeriod.LAST_MONTH.range(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertThat(CreditorPeriod.LAST_MONTH.previous(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        assertThat(CreditorPeriod.LAST_3_MONTHS.range(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 9, 1), today));
        assertThat(CreditorPeriod.LAST_3_MONTHS.previous(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 26)));
        assertThat(CreditorPeriod.THIS_YEAR.previous(today))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 11, 26)));
        // mês anterior de fevereiro termina no último dia de janeiro (não no dia 28)
        assertThat(CreditorPeriod.LAST_MONTH.previous(LocalDate.of(2027, 3, 10)))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)));
        // 31/03: o mês corrente inteiro compara com fevereiro inteiro
        assertThat(CreditorPeriod.THIS_MONTH.previous(LocalDate.of(2027, 3, 31)))
                .isEqualTo(new CreditorPeriod.Range(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28)));
    }

    @Test
    void periodoForaDaListaERecusadoEVazioEOMesCorrente() {
        assertThat(CreditorPeriod.parse(null)).isEqualTo(CreditorPeriod.THIS_MONTH);
        assertThat(CreditorPeriod.parse("LAST_3_MONTHS")).isEqualTo(CreditorPeriod.LAST_3_MONTHS);
        assertThatThrownBy(() -> CreditorPeriod.parse("this_month")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> CreditorPeriod.parse("LAST_10_YEARS")).isInstanceOf(BusinessRuleException.class);
    }

    private static PortfolioShare share(String name, String value) {
        return new PortfolioShare(UUID.nameUUIDFromBytes(name.getBytes()), name, new BigDecimal(value));
    }
}
