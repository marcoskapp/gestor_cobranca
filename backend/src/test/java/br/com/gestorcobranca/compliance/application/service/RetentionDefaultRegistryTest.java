package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.AppliedRetention;
import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults;
import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults.RetentionDefault;
import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionDefaultRegistryTest {

    private static RetentionDefaults portal(int challenges) {
        return () -> List.of(new RetentionDefault("PORTAL_ACCESS_CHALLENGES", challenges),
                new RetentionDefault("PORTAL_ACCESS_THROTTLES", 30),
                new RetentionDefault("PORTAL_SESSION_CLIENT_DATA", 365));
    }

    private static final RetentionDefaults SCORING = () -> List.of(new RetentionDefault("SCORING_CASE_FACTS", 1095),
            new RetentionDefault("SCORING_FEATURE_SNAPSHOTS", 730));

    @Test
    void collectsTheDefaultOfEachCategoryWithSystemDefault() {
        RetentionDefaultRegistry registry = new RetentionDefaultRegistry(List.of(portal(30), SCORING));

        assertThat(registry.defaultFor(RetentionCategory.PORTAL_ACCESS_CHALLENGES)).hasValue(30);
        assertThat(registry.defaultFor(RetentionCategory.SCORING_CASE_FACTS)).hasValue(1095);
        assertThat(registry.defaultFor(RetentionCategory.DEBTOR_INACTIVE)).isEmpty();
    }

    @Test
    void inconsistentDefaultsPreventStartup() {
        assertThatThrownBy(() -> new RetentionDefaultRegistry(List.of(portal(30))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SCORING_CASE_FACTS");
        assertThatThrownBy(() -> new RetentionDefaultRegistry(List.of(portal(0), SCORING)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fora dos limites");
        assertThatThrownBy(() -> new RetentionDefaultRegistry(List.of(portal(30), portal(30), SCORING)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mais de uma vez");
        List<RetentionDefaults> withMonthsCategory = new ArrayList<>(List.of(portal(30), SCORING));
        withMonthsCategory.add(() -> List.of(new RetentionDefault("IMPORT_FILES", 6)));
        assertThatThrownBy(() -> new RetentionDefaultRegistry(withMonthsCategory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("não aceita padrão");
        assertThatThrownBy(() -> new RetentionDefaultRegistry(List.of(() -> List.of(new RetentionDefault("X", 1)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("desconhecida");
    }

    @Test
    void ownerModuleRequirementAboveTheCategoryMinimumPreventsStartup() {
        // janela de fatos do score maior que o mínimo da categoria (730 dias): uma assessoria poderia apagar fatos em uso
        RetentionDefaults scoringWithLongLookback = () -> List.of(new RetentionDefault("SCORING_CASE_FACTS", 1095, 731),
                new RetentionDefault("SCORING_FEATURE_SNAPSHOTS", 730));
        assertThatThrownBy(() -> new RetentionDefaultRegistry(List.of(portal(30), scoringWithLongLookback)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCORING_CASE_FACTS").hasMessageContaining("731")
                .hasMessageContaining("app.scoring.fact-lookback-days");
        RetentionDefaults scoringAtTheMinimum = () -> List.of(new RetentionDefault("SCORING_CASE_FACTS", 1095, 730),
                new RetentionDefault("SCORING_FEATURE_SNAPSHOTS", 730));
        assertThat(new RetentionDefaultRegistry(List.of(portal(30), scoringAtTheMinimum))
                .defaultFor(RetentionCategory.SCORING_CASE_FACTS)).hasValue(1095);
    }

    @Test
    void appliedRetentionCutsInTheUnitOfTheCategory() {
        Instant now = Instant.parse("2026-03-31T15:00:00Z");

        assertThat(new AppliedRetention("PORTAL_ACCESS_CHALLENGES", 30, "DAYS", AppliedRetention.TENANT).cutoff(now))
                .isEqualTo(Instant.parse("2026-03-01T15:00:00Z"));
        // meses de calendário no fuso de operação: 31/03 − 1 mês = 28/02
        assertThat(new AppliedRetention("IMPORT_FILES", 1, "MONTHS", AppliedRetention.TENANT).cutoff(now))
                .isEqualTo(Instant.parse("2026-02-28T15:00:00Z"));
        assertThat(new AppliedRetention("IMPORT_FILES", 1, "MONTHS", AppliedRetention.TENANT).describe())
                .isEqualTo("1 mês");
        assertThat(new AppliedRetention("SCORING_CASE_FACTS", 730, "DAYS", AppliedRetention.SYSTEM_DEFAULT).describe())
                .isEqualTo("730 dias");
    }
}
