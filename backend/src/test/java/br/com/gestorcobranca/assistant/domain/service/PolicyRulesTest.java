package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.ServiceWindow;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validação da política, resolução por escopo, janelas de atendimento e amostragem. */
class PolicyRulesTest {

    private static PolicySettings with(String identification, Set<String> channels) {
        return new PolicySettings(AssistantMode.AUTONOMOUS, channels, null, null, null, null, null, null, null, null, null,
                null, null, null, null, Map.of("DISPUTE", "juridico"), identification, null, null, null, null, null, null, null);
    }

    @Test
    void defaultsAreSafe() {
        PolicySettings s = PolicySettings.defaults(AssistantMode.SHADOW);
        assertThat(s.requireIdentityVerification()).isTrue();
        assertThat(s.channels()).containsExactly("WHATSAPP");
        assertThat(s.allowCreditBureauMention()).isFalse();
        assertThat(s.identificationMessage()).contains("assistente virtual");
        assertThat(s.requiredProfileFor(HandoffReason.LEGAL_MENTION)).isEqualTo("SUPERVISOR");
    }

    @Test
    void transparencyMessageIsMandatory() {
        assertThatThrownBy(() -> with("Olá, sou a Ana do financeiro.", null).normalized())
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("assistente virtual");
    }

    @Test
    void onlyFreeTextChannelsAreAccepted() {
        assertThatThrownBy(() -> with(null, Set.of("SMS")).normalized()).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void requiredProfileOverridesReasonDefault() {
        assertThat(with(null, null).normalized().requiredProfileFor(HandoffReason.DISPUTE)).isEqualTo("JURIDICO");
    }

    @Test
    void mostSpecificPublishedPolicyWins() {
        UUID creditor = UUID.randomUUID();
        UUID portfolio = UUID.randomUUID();
        AssistantPolicy tenant = published(null, null, "geral");
        AssistantPolicy byCreditor = published(creditor, null, "credor");
        AssistantPolicy byPortfolio = published(creditor, portfolio, "carteira");
        List<AssistantPolicy> all = List.of(tenant, byCreditor, byPortfolio);
        assertThat(PolicyResolver.resolve(all, creditor, portfolio)).contains(byPortfolio);
        assertThat(PolicyResolver.resolve(all, creditor, UUID.randomUUID())).contains(byCreditor);
        assertThat(PolicyResolver.resolve(all, UUID.randomUUID(), null)).contains(tenant);
    }

    @Test
    void serviceHoursAndNextOpening() {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");
        List<ServiceWindow> hours = List.of(new ServiceWindow(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                LocalTime.of(8, 0), LocalTime.of(20, 0)));
        ZonedDateTime mondayNight = ZonedDateTime.of(2026, 10, 26, 21, 0, 0, 0, zone);
        assertThat(ServiceHours.isOpen(hours, mondayNight)).isFalse();
        assertThat(ServiceHours.isOpen(hours, mondayNight.withHour(9))).isTrue();
        assertThat(ServiceHours.nextOpening(hours, mondayNight)).contains(mondayNight.plusDays(1).withHour(8));
        assertThat(ServiceHours.isOpen(List.of(), mondayNight)).isTrue();
    }

    @Test
    void samplingIsDeterministicAndBounded() {
        UUID turn = UUID.randomUUID();
        assertThat(ReviewSampler.sampled(turn, new BigDecimal("0.3"))).isEqualTo(ReviewSampler.sampled(turn, new BigDecimal("0.3")));
        assertThat(ReviewSampler.sampled(turn, BigDecimal.ONE)).isTrue();
        assertThat(ReviewSampler.sampled(turn, BigDecimal.ZERO)).isFalse();
        long hits = java.util.stream.IntStream.range(0, 5000)
                .filter(i -> ReviewSampler.sampled(UUID.randomUUID(), new BigDecimal("0.10"))).count();
        assertThat(hits).isBetween(350L, 650L);
    }

    private static AssistantPolicy published(UUID creditor, UUID portfolio, String code) {
        PolicySettings s = PolicySettings.defaults(AssistantMode.AUTONOMOUS);
        AssistantPolicy p = new AssistantPolicy(creditor, portfolio, code, code, s, "{}");
        p.publish(Instant.now(), UUID.randomUUID(), 1);
        return p;
    }
}
