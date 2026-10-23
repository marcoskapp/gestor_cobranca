package br.com.gestorcobranca.campaign;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ArmResult;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentInput;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentResults;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentView;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.MetricResult;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.StepOverride;
import br.com.gestorcobranca.campaign.domain.service.VariantAllocator;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concorrência entre campanhas do mesmo grupo de exclusividade (prioridade substitui, empate suprime,
 * sem grupo convive) e teste A/B com grupo de controle, atribuição estável e resultados por braço.
 */
class CampaignConcurrencyAndExperimentIT extends CampaignTestSupport {

    private static final String[] CPFS = {"529.982.247-25", "111.444.777-35", "390.533.447-05", "853.513.468-93",
            "714.602.380-01", "286.255.878-87", "457.508.000-40", "123.456.789-09"};

    UUID segmentId;

    @BeforeEach
    void setUp() {
        provisionCampaigns("cex");
        segmentId = segment(creditorA, "TODOS", SegmentCriteria.any());
    }

    private CadenceDefinition twoSteps() {
        return cadence(null, whatsapp("D0", 0, null, null), whatsapp("D5", 5 * 1440, null, null));
    }

    @Test
    void higherPriorityPreemptsEqualPrioritySuppressesAndUngroupedCampaignsCoexist() {
        UUID debtor = debtor(CPFS[0]);
        CaseView kase = loadCase(creditorA, portfolioA, debtor, "300.00", LocalDate.of(2026, 5, 1));

        CampaignView low = activeCampaign("BAIXA", creditorA, settings(segmentId, "COBRANCA", 10), twoSteps());
        runEngine();
        assertThat(enrollmentOf(low.id(), kase.id()).status()).isEqualTo("ACTIVE");

        CampaignView high = activeCampaign("ALTA", creditorA, settings(segmentId, "COBRANCA", 200), twoSteps());
        runEngine();
        EnrollmentView preempted = enrollmentOf(low.id(), kase.id());
        assertThat(preempted.status()).isEqualTo("ENDED");
        assertThat(preempted.endReason()).isEqualTo("PREEMPTED");
        assertThat(enrollmentOf(high.id(), kase.id()).status()).isEqualTo("ACTIVE");

        CampaignView tie = activeCampaign("EMPATE", creditorA, settings(segmentId, "COBRANCA", 200), twoSteps());
        runEngine();
        assertThat(enrollmentsOf(kase.id())).noneMatch(e -> e.campaignId().equals(tie.id()));
        assertThat(asManager(() -> execution.panel(tie.id())).suppressedByConcurrency()).isEqualTo(1);
        assertThat(count("select count(*) from campaign.enrollment_suppression where campaign_id = ? "
                + "and blocking_campaign_id = ?", tie.id(), high.id())).isEqualTo(1);

        CampaignView free = activeCampaign("LEMBRETE", creditorA, settings(segmentId, null, 1), twoSteps());
        runEngine();
        assertThat(enrollmentOf(free.id(), kase.id()).status()).isEqualTo("ACTIVE");
        // só a inscrição em andamento do grupo e a sem grupo estão ativas
        assertThat(enrollmentsOf(kase.id()).stream().filter(e -> e.status().equals("ACTIVE")).map(e -> e.campaignId()))
                .containsExactlyInAnyOrder(high.id(), free.id());
    }

    @Test
    void abTestAssignsStableArmsWithHoldoutAndReportsComparableMetrics() {
        publishedTemplate(null, "COBRANCA_B", "WHATSAPP");
        List<UUID> debtors = new ArrayList<>();
        List<CaseView> cases = new ArrayList<>();
        for (int i = 0; i < CPFS.length; i++) {
            UUID debtor = debtorWithPhone(CPFS[i], i);
            debtors.add(debtor);
            cases.add(loadCase(creditorA, portfolioA, debtor, "200.00", LocalDate.of(2026, 6, 1)));
        }
        CampaignView campaign = asManager(() -> campaigns.create(new CreateCampaignCommand("AB", creditorA, null,
                settings(segmentId, "COBRANCA", 100))));
        asManager(() -> campaigns.saveDraft(campaign.id(), twoSteps()));
        asManager(() -> campaigns.publishDraft(campaign.id()));
        ExperimentView experiment = asManager(() -> experiments.create(campaign.id(), new ExperimentInput("Texto B",
                "Texto direto aumenta pagamento", "PAYMENT", new BigDecimal("40"), List.of(
                new ExperimentVariant("A", "Atual", 1, null),
                new ExperimentVariant("B", "Texto B", 1, List.of(new StepOverride("D0", null, "COBRANCA_B", null, null)))),
                30, 100)));
        asManager(() -> experiments.start(experiment.id()));
        asManager(() -> execution.activate(campaign.id()));
        runEngine();

        String salt = system(() -> jdbcTemplate.queryForObject("select salt from campaign.experiment where id = ?",
                String.class, experiment.id()));
        Map<String, Long> arms = new java.util.HashMap<>();
        for (int i = 0; i < debtors.size(); i++) {
            VariantAllocator.Arm expected = VariantAllocator.allocate(salt, debtors.get(i), new BigDecimal("40"),
                    experiment.variants());
            EnrollmentView e = enrollmentOf(campaign.id(), cases.get(i).id());
            assertThat(e.variantCode()).isEqualTo(expected.code());
            assertThat(e.control()).isEqualTo(expected.control());
            arms.merge(expected.code(), 1L, Long::sum);
            List<String> templates = system(() -> jdbcTemplate.queryForList("""
                    select t.code from communication.message m join communication.message_template t on t.id = m.template_id
                     where m.campaign_execution_id = ?
                    """, String.class, e.id()));
            if (expected.control()) {
                assertThat(e.status()).isEqualTo("HOLDOUT");
                assertThat(templates).isEmpty();
            } else {
                assertThat(templates).containsExactly(expected.code().equals("B") ? "COBRANCA_B" : "COBRANCA");
            }
        }
        assertThat(count("select count(*) from campaign.experiment_assignment where experiment_id = ?", experiment.id()))
                .isEqualTo(debtors.size());
        assertThat(count("select count(*) from shared.business_event where event_type = 'experiment.variant_assigned' "
                + "and attributes ->> 'experimentId' = ?", experiment.id().toString())).isEqualTo(debtors.size());

        // reavaliação não muda braços nem duplica atribuições
        asManager(() -> execution.pause(campaign.id(), "Reavaliar"));
        asManager(() -> execution.activate(campaign.id()));
        runEngine();
        assertThat(count("select count(*) from campaign.experiment_assignment where experiment_id = ?", experiment.id()))
                .isEqualTo(debtors.size());

        // pagamento de um devedor do primeiro braço tratado
        int paidIndex = 0;
        while (enrollmentOf(campaign.id(), cases.get(paidIndex).id()).control()) {
            paidIndex++;
        }
        EnrollmentView paid = enrollmentOf(campaign.id(), cases.get(paidIndex).id());
        UUID paidDebtor = debtors.get(paidIndex);
        publishAndAwait(new PaymentConfirmed(Ids.newId(), tenant, Instant.now(), Ids.newId(), paidDebtor, creditorA,
                portfolioA, null, new BigDecimal("80.00"), Instant.now(), "EXTERNAL", "PIX", List.of()),
                "campaign.payment-confirmed");

        ExperimentResults all = asManager(() -> experiments.results(experiment.id(), false));
        assertThat(all.baselineArm()).isEqualTo(ExperimentVariant.CONTROL);
        assertThat(all.arms()).extracting(ArmResult::arm).containsExactly("CONTROL", "A", "B");
        assertThat(all.arms().stream().mapToLong(ArmResult::debtors).sum()).isEqualTo(debtors.size());
        for (ArmResult arm : all.arms()) {
            assertThat(arm.debtors()).isEqualTo(arms.getOrDefault(arm.arm(), 0L));
            MetricResult payment = arm.metrics().stream().filter(m -> m.metric().equals("PAYMENT")).findFirst().orElseThrow();
            assertThat(payment.denominator()).isEqualTo(arm.debtors());
            assertThat(payment.successes()).isEqualTo(arm.arm().equals(paid.variantCode()) ? 1 : 0);
            assertThat(payment.warnings()).contains("SAMPLE_BELOW_MINIMUM");
            MetricResult recovered = arm.metrics().stream().filter(m -> m.metric().equals("RECOVERED_PER_DEBTOR"))
                    .findFirst().orElseThrow();
            if (arm.arm().equals(paid.variantCode())) {
                assertThat(recovered.estimate()).isEqualByComparingTo(
                        new BigDecimal("80.00").divide(BigDecimal.valueOf(arm.debtors()), 2, java.math.RoundingMode.HALF_EVEN));
            }
            if (!arm.control()) {
                assertThat(payment.difference()).isNotNull();
            }
        }
        assertThat(all.warnings()).contains("INSUFFICIENT_SAMPLE");
        // amostra madura (inscritos há 30 dias ou mais): ninguém ainda
        ExperimentResults mature = asManager(() -> experiments.results(experiment.id(), true));
        assertThat(mature.arms()).allMatch(a -> a.debtors() == 0);
    }
}
