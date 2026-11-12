package br.com.gestorcobranca.incentive.domain.model;

import br.com.gestorcobranca.incentive.domain.model.tier.Tier;
import br.com.gestorcobranca.incentive.domain.model.tier.TierAssignment;
import br.com.gestorcobranca.incentive.domain.model.tier.TierMatrix;
import br.com.gestorcobranca.incentive.domain.model.tier.TierPeriodicity;
import br.com.gestorcobranca.incentive.domain.model.tier.TierProposal;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRound;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRoundStatus;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Matriz, parâmetros, rodada e ajuste da classificação de desempenho. */
class TierRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Test
    void matrizPadraoDoDesenho() {
        TierMatrix m = TierMatrix.standard();

        assertThat(m.tierFor(4, 4)).isEqualTo(Tier.A);
        assertThat(m.tierFor(4, 1)).isEqualTo(Tier.C);
        assertThat(m.tierFor(3, 3)).isEqualTo(Tier.B);
        assertThat(m.tierFor(2, 4)).isEqualTo(Tier.B);
        assertThat(m.tierFor(1, 1)).isEqualTo(Tier.D);
        assertThat(m.toMap().get("Q4")).containsExactly(Map.entry("Q4", "A"), Map.entry("Q3", "A"),
                Map.entry("Q2", "B"), Map.entry("Q1", "C"));
        assertThat(TierMatrix.of(m.toMap())).isEqualTo(m);
    }

    @Test
    void matrizParametrizavelAceitaSoAsDezesseisCelulasDeAAD() {
        Map<String, Map<String, String>> custom = TierMatrix.standard().toMap();
        custom.get("Q1").put("Q1", "c");
        assertThat(TierMatrix.of(custom).tierFor(1, 1)).isEqualTo(Tier.C);

        Map<String, Map<String, String>> invalidTier = TierMatrix.standard().toMap();
        invalidTier.get("Q2").put("Q3", "E");
        assertThatThrownBy(() -> TierMatrix.of(invalidTier)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "tier_settings.matrix_invalid");

        Map<String, Map<String, String>> missingCell = TierMatrix.standard().toMap();
        missingCell.get("Q3").remove("Q2");
        assertThatThrownBy(() -> TierMatrix.of(missingCell)).isInstanceOf(BusinessRuleException.class);

        Map<String, Map<String, String>> missingRow = new LinkedHashMap<>(TierMatrix.standard().toMap());
        missingRow.remove("Q4");
        assertThatThrownBy(() -> TierMatrix.of(missingRow)).isInstanceOf(BusinessRuleException.class);

        Map<String, Map<String, String>> extraRow = new LinkedHashMap<>(TierMatrix.standard().toMap());
        extraRow.put("Q5", Map.of("Q1", "A", "Q2", "A", "Q3", "A", "Q4", "A"));
        assertThatThrownBy(() -> TierMatrix.of(extraRow)).isInstanceOf(BusinessRuleException.class);

        Map<String, Map<String, String>> blank = TierMatrix.standard().toMap();
        blank.get("Q4").put("Q4", null);
        assertThatThrownBy(() -> TierMatrix.of(blank)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void parametrosPadraoELimites() {
        TierSettings d = TierSettings.defaults();
        assertThat(d.windowDays()).isEqualTo(90);
        assertThat(d.minEvaluations()).isEqualTo(5);
        assertThat(d.minCases()).isEqualTo(30);
        assertThat(d.qualityGate()).isEqualByComparingTo("7.0");
        assertThat(d.defaultTier()).isEqualTo(Tier.B);
        assertThat(d.periodicity()).isEqualTo(TierPeriodicity.MONTHLY);
        assertThat(d.reviewBusinessDays()).isEqualTo(3);

        assertThatThrownBy(() -> settings(29, "7", 3)).hasFieldOrPropertyWithValue("code", "tier_settings.window_invalid");
        assertThatThrownBy(() -> settings(366, "7", 3)).hasFieldOrPropertyWithValue("code", "tier_settings.window_invalid");
        assertThatThrownBy(() -> settings(90, "10.01", 3))
                .hasFieldOrPropertyWithValue("code", "tier_settings.quality_gate_invalid");
        assertThatThrownBy(() -> settings(90, "7.005", 3))
                .hasFieldOrPropertyWithValue("code", "tier_settings.quality_gate_invalid");
        assertThatThrownBy(() -> settings(90, "7", 0)).hasFieldOrPropertyWithValue("code", "tier_settings.review_days_invalid");
        assertThat(settings(365, "10", 20).windowDays()).isEqualTo(365);
    }

    @Test
    void rodadaComJanelaAteOntem() {
        TierRound round = round();

        assertThat(round.periodTo()).isEqualTo(TODAY.minusDays(1));
        assertThat(round.periodFrom()).isEqualTo(TODAY.minusDays(90));
        assertThat(round.status()).isEqualTo(TierRoundStatus.PROPOSED);
    }

    @Test
    void publicacaoSoNoPrazoEUmaVez() {
        TierRound round = round();
        UUID by = UUID.randomUUID();

        round.publish(by, NOW, TODAY.plusDays(5));

        assertThat(round.status()).isEqualTo(TierRoundStatus.PUBLISHED);
        assertThat(round.validFrom()).isEqualTo(TODAY.plusDays(5));
        assertThatThrownBy(() -> round.publish(by, NOW, TODAY.plusDays(5))).isInstanceOf(ConflictException.class);

        TierRound late = round();
        assertThatThrownBy(() -> late.publish(by, NOW, TODAY.plusDays(6)))
                .hasFieldOrPropertyWithValue("code", "tier_round.review_expired");
    }

    @Test
    void expiraSoDepoisDoPrazo() {
        TierRound round = round();

        assertThatThrownBy(() -> round.expire(NOW, TODAY.plusDays(5))).isInstanceOf(ConflictException.class);
        round.expire(NOW, TODAY.plusDays(6));

        assertThat(round.status()).isEqualTo(TierRoundStatus.EXPIRED);
        assertThatThrownBy(() -> round.requireOpen(TODAY)).isInstanceOf(ConflictException.class);
    }

    @Test
    void ajusteExigeMotivo() {
        TierProposal proposal = new TierProposal(UUID.randomUUID(), 3, 3, false, 10, new BigDecimal("8.50"), 40,
                new BigDecimal("1.1000"), Tier.B);
        TierAssignment assignment = TierAssignment.proposed(UUID.randomUUID(), proposal);
        UUID by = UUID.randomUUID();

        assertThat(assignment.finalTier()).isEqualTo(Tier.B);
        assertThat(assignment.adjusted()).isFalse();
        assertThatThrownBy(() -> assignment.adjust(Tier.A, "curto", by, NOW))
                .hasFieldOrPropertyWithValue("code", "tier_assignment.reason_invalid");
        assertThatThrownBy(() -> assignment.adjust(Tier.A, " ".repeat(20), by, NOW))
                .isInstanceOf(BusinessRuleException.class);

        assignment.adjust(Tier.D, "  Licença médica durante metade da janela  ", by, NOW);

        assertThat(assignment.finalTier()).isEqualTo(Tier.D);
        assertThat(assignment.proposal().proposedTier()).isEqualTo(Tier.B);
        assertThat(assignment.adjustmentReason()).isEqualTo("Licença médica durante metade da janela");
        assertThat(assignment.adjustedBy()).isEqualTo(by);
    }

    @Test
    void nivelPorTexto() {
        assertThat(Tier.parse(" a ", "x")).isEqualTo(Tier.A);
        assertThatThrownBy(() -> Tier.parse("E", "x")).hasFieldOrPropertyWithValue("code", "x");
        assertThatThrownBy(() -> Tier.parse(null, "x")).isInstanceOf(BusinessRuleException.class);
    }

    private static TierRound round() {
        // prazo: 3 dias úteis depois de quinta 12/11 = terça 17/11
        return TierRound.propose(TODAY, TierRound.Trigger.SCHEDULED, TierSettings.defaults(), TODAY.plusDays(5), NOW,
                null);
    }

    private static TierSettings settings(int window, String gate, int reviewDays) {
        return new TierSettings(window, 5, 30, new BigDecimal(gate), TierMatrix.standard(), Tier.B,
                TierPeriodicity.WEEKLY, reviewDays);
    }
}
