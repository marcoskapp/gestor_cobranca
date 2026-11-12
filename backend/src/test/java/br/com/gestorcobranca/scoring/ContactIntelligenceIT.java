package br.com.gestorcobranca.scoring;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases.AgreementBucketView;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases.ContactInsightsView;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases.ContactProfileView;
import br.com.gestorcobranca.scoring.application.service.ScoringTestHooks;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inteligência de contato ({@code docs/tecnico/desenho/inteligencia-de-contato.md} §8): eventos com contato →
 * fatos e perfil; ligação + interação da telefonia (ou do assistente) contam uma tentativa só; resposta marca só a
 * tentativa mais recente do mesmo credor; sem base legal nada é coletado; contato de outro devedor é ignorado; acordo
 * cumprido conta e quebrado não (e só a partir da formalização); escopo por credor no painel; base das taxas;
 * anonimização e retenção em rodadas.
 */
class ContactIntelligenceIT extends ScoringTestSupport {

    @Autowired
    ContactIntelligenceUseCases intelligence;
    @Autowired
    DebtorContactUseCases contacts;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        provision("cint");
    }

    @Test
    void withoutLegalBasisNothingIsCollected() {
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        UUID phone = phoneOf(c.debtorId());
        publishAndAwait(call(c, phone, "OUTBOUND", "ANSWERED", 60, Instant.now()), "scoring.contact.call");
        publishAndAwait(agreement(c, Ids.newId(), "FORMALIZED", null, Instant.now()), "scoring.contact.agreement");

        assertThat(count("select count(*) from scoring.contact_attempt")).isZero();
        assertThat(count("select count(*) from scoring.contact_profile")).isZero();
        assertThat(count("select count(*) from scoring.agreement_contact_outcome")).isZero();
    }

    @Test
    void eventsWithContactBuildTheProfileWithoutCountingTwice() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        UUID phone = phoneOf(c.debtorId());
        Instant now = Instant.now();

        // ligação atendida e a interação que a telefonia registra no caso: uma tentativa só
        publishAndAwait(call(c, phone, "OUTBOUND", "ANSWERED", 90, now.minusSeconds(7200)), "scoring.contact.call");
        publishAndAwait(interaction(c, phone, "OUTBOUND", true, "TELEPHONY", now.minusSeconds(7200)),
                "scoring.contact.interaction");
        // evento de versão anterior (sem origem): ignorado
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, now, Ids.newId(), c.id(), c.debtorId(),
                c.creditorId(), "PHONE", "CPC", true, operatorX, "OUTBOUND", c.portfolioId()),
                "scoring.contact.interaction");
        assertThat(count("select count(*) from scoring.contact_attempt where kind = 'ATTEMPT'")).isEqualTo(1);

        // mais quatro ligações sem atendimento e uma interação manual efetiva
        for (int i = 1; i <= 4; i++) {
            publishAndAwait(call(c, phone, "OUTBOUND", "NO_ANSWER", 0, now.minusSeconds(7200L + i * 600)),
                    "scoring.contact.call");
        }
        publishAndAwait(interaction(c, phone, "OUTBOUND", true, "MANUAL", now.minusSeconds(600)),
                "scoring.contact.interaction");
        // contato de outro devedor: ignorado (defesa em profundidade)
        publishAndAwait(call(c, Ids.newId(), "OUTBOUND", "ANSWERED", 30, now), "scoring.contact.call");
        assertThat(count("select count(*) from scoring.contact_attempt where kind = 'ATTEMPT'")).isEqualTo(6);

        // WhatsApp: enviada, lida (não conta) e respondida pelo devedor → a tentativa conta como respondida
        UUID sent = Ids.newId();
        publishAndAwait(message(c, phone, sent, "OUTBOUND", "SENT", now.minusSeconds(3600)), "scoring.contact.message");
        publishAndAwait(message(c, phone, sent, "OUTBOUND", "READ", now.minusSeconds(3000)), "scoring.contact.message");
        publishAndAwait(message(c, phone, Ids.newId(), "INBOUND", "RECEIVED", now.minusSeconds(1800)),
                "scoring.contact.message");
        assertThat(count("select count(*) from scoring.contact_attempt where channel = 'WHATSAPP' and kind = 'ATTEMPT' "
                + "and success and responded_by_event_id is not null")).isEqualTo(1);

        system(() -> ScoringTestHooks.refreshContactRateBase(applicationContext));
        ContactProfileView profile = asManager(manager, () -> intelligence.profile(c.debtorId(), c.id()));
        assertThat(profile.totalAttempts()).isEqualTo(7);
        assertThat(profile.sufficient()).isTrue();
        assertThat(profile.contacts()).allSatisfy(v -> assertThat(v.contactId()).isEqualTo(phone));
        ContactIntelligenceUseCases.ContactView calls = profile.contacts().stream()
                .filter(v -> v.channel().equals("PHONE")).findFirst().orElseThrow();
        assertThat(calls.attempts()).isEqualTo(6);
        assertThat(calls.successes()).isEqualTo(2);
        assertThat(calls.confidence()).isEqualTo("MEDIUM");
        assertThat(calls.explanation()).startsWith("2 de 6 ligações atendidas");
        ContactIntelligenceUseCases.ContactView whatsapp = profile.contacts().stream()
                .filter(v -> v.channel().equals("WHATSAPP")).findFirst().orElseThrow();
        assertThat(whatsapp.successes()).isEqualTo(1);
        // a mensagem recebida respondeu à enviada: conta como sucesso da tentativa, não como contato iniciado
        assertThat(whatsapp.debtorInitiated()).isZero();
        assertThat(whatsapp.reads()).isEqualTo(1);
        assertThat(whatsapp.confidence()).isEqualTo("LOW");
        assertThat(profile.preferredChannel()).isNotNull();
        assertThat(profile.notice()).contains("não muda a política de contato");
    }

    @Test
    void assistantInteractionMirrorsTheConversationAndIsNotCounted() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        double mirrored = ignored("mirrored");

        publishAndAwait(interaction(c, phoneOf(c.debtorId()), "OUTBOUND", true, "ASSISTANT", Instant.now()),
                "scoring.contact.interaction");

        assertThat(count("select count(*) from scoring.contact_attempt")).isZero();
        assertThat(ignored("mirrored")).isEqualTo(mirrored + 1);
    }

    @Test
    void responseMarksOnlyTheLatestAttemptOfTheSameCreditor() {
        enableScoring();
        CaseView a = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        CaseView b = loadCase(creditorB, portfolioB, CPFS[0], "700.00", 30);
        assertThat(b.debtorId()).isEqualTo(a.debtorId());
        UUID phone = phoneOf(a.debtorId());
        Instant now = Instant.now();
        publishAndAwait(message(b, phone, Ids.newId(), "OUTBOUND", "SENT", now.minusSeconds(6 * 3600)),
                "scoring.contact.message");
        publishAndAwait(message(a, phone, Ids.newId(), "OUTBOUND", "SENT", now.minusSeconds(5 * 3600)),
                "scoring.contact.message");
        publishAndAwait(message(a, phone, Ids.newId(), "OUTBOUND", "SENT", now.minusSeconds(4 * 3600)),
                "scoring.contact.message");
        // três mensagens seguidas do devedor no credor A
        for (int h = 3; h >= 1; h--) {
            publishAndAwait(message(a, phone, Ids.newId(), "INBOUND", "RECEIVED", now.minusSeconds(h * 3600L)),
                    "scoring.contact.message");
        }

        // só a tentativa mais recente do credor A é marcada; a mais antiga de A e a do credor B, não
        assertThat(count("select count(*) from scoring.contact_attempt where kind = 'ATTEMPT' and success "
                + "and creditor_id = ?", creditorA)).isEqualTo(1);
        assertThat(count("select count(*) from scoring.contact_attempt where kind = 'ATTEMPT' and success "
                + "and creditor_id = ? and occurred_at > ?", creditorA,
                java.sql.Timestamp.from(now.minusSeconds(4 * 3600 + 60)))).isEqualTo(1);
        assertThat(count("select count(*) from scoring.contact_attempt where kind = 'ATTEMPT' and success "
                + "and creditor_id = ?", creditorB)).isZero();

        ContactProfileView profile = asManager(manager, () -> intelligence.profile(a.debtorId(), a.id()));
        ContactIntelligenceUseCases.ContactView whatsapp = profile.contacts().stream()
                .filter(v -> v.channel().equals("WHATSAPP")).findFirst().orElseThrow();
        assertThat(whatsapp.attempts()).isEqualTo(2);
        assertThat(whatsapp.successes()).isEqualTo(1);
        assertThat(whatsapp.debtorInitiated()).isZero();
        // painel: as respostas também não contam como contato iniciado
        LocalDate today = LocalDate.now(ZONE);
        assertThat(asManager(manager, () -> intelligence.insights(today.minusDays(1), today, creditorA, null, null))
                .totals().debtorInitiated()).isZero();
    }

    @Test
    void rateBaseIsMaterializedByTheMaintenance() {
        enableScoring();
        CaseView a = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        CaseView b = loadCase(creditorA, portfolioA, CPFS[1], "900.00", 20);
        UUID phoneA = phoneOf(a.debtorId());
        Instant now = Instant.now();
        // fora da janela de 180 dias: não entra na base (senão seria o 1º atendimento da série)
        publishAndAwait(call(a, phoneA, "OUTBOUND", "ANSWERED", 60, now.minus(Duration.ofDays(200))),
                "scoring.contact.call");
        publishAndAwait(call(a, phoneA, "OUTBOUND", "NO_ANSWER", 0, now.minusSeconds(3 * 3600)), "scoring.contact.call");
        publishAndAwait(call(a, phoneA, "OUTBOUND", "ANSWERED", 60, now.minusSeconds(2 * 3600)), "scoring.contact.call");
        publishAndAwait(call(b, phoneOf(b.debtorId()), "OUTBOUND", "ANSWERED", 60, now.minusSeconds(3600)),
                "scoring.contact.call");

        // antes da primeira manutenção: base vazia, sem mediana (priori neutra)
        assertThat(asManager(manager, () -> intelligence.profile(a.debtorId(), a.id()))
                .portfolioMedianAttemptsToFirstSuccess()).isNull();

        assertThat(system(() -> ScoringTestHooks.refreshContactRateBase(applicationContext))).isEqualTo(3);
        assertThat(count("select attempts from scoring.contact_rate_base where portfolio_id = ? and channel = 'PHONE'",
                portfolioA)).isEqualTo(3);
        assertThat(count("select successes from scoring.contact_rate_base where portfolio_id is null "
                + "and channel = 'PHONE'")).isEqualTo(2);
        assertThat(count("select attempts from scoring.contact_rate_base where portfolio_id = ? and channel is null",
                portfolioA)).isEqualTo(3);
        // séries: 2ª tentativa (devedor a) e 1ª (devedor b) → mediana 1,5
        assertThat(asManager(manager, () -> intelligence.profile(a.debtorId(), a.id()))
                .portfolioMedianAttemptsToFirstSuccess()).isEqualByComparingTo(new BigDecimal("1.5"));
        // recalcular substitui (não acumula)
        assertThat(system(() -> ScoringTestHooks.refreshContactRateBase(applicationContext))).isEqualTo(3);
        assertThat(count("select count(*) from scoring.contact_rate_base")).isEqualTo(3);
    }

    @Test
    void scopeByCreditorInProfileAndInsights() {
        enableScoring();
        CaseView a = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        CaseView b = loadCase(creditorB, portfolioB, CPFS[1], "700.00", 30);
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            publishAndAwait(call(a, phoneOf(a.debtorId()), "OUTBOUND", "ANSWERED", 30, now.minusSeconds(600L * (i + 1))),
                    "scoring.contact.call");
        }
        publishAndAwait(call(b, phoneOf(b.debtorId()), "OUTBOUND", "NO_ANSWER", 0, now.minusSeconds(300)),
                "scoring.contact.call");

        LocalDate today = LocalDate.now(ZONE);
        ContactInsightsView all = asManager(manager, () -> intelligence.insights(today.minusDays(7), today, null, null,
                null));
        assertThat(all.totals().attempts()).isEqualTo(4);
        assertThat(all.totals().successes()).isEqualTo(3);
        assertThat(all.byPersonType()).singleElement().satisfies(p -> assertThat(p.personType()).isEqualTo("INDIVIDUAL"));
        // contatos da carga que criou o devedor nunca são novos (contatos-recebidos.md)
        assertThat(all.byContactOrigin()).singleElement().satisfies(o -> {
            assertThat(o.origin()).isEqualTo("EXISTING");
            assertThat(o.attempts()).isEqualTo(4);
        });
        assertThat(all.firstSuccessCurve()).first().satisfies(p -> {
            assertThat(p.attempts()).isEqualTo(1);
            assertThat(p.contacts()).isEqualTo(1);
        });
        assertThat(all.neverAnswered()).isEqualTo(1);

        // operador restrito ao credor B: só os fatos de B; pedir o credor A devolve vazio
        ContactInsightsView onlyB = asOperator(operatorZ, () -> intelligence.insights(today.minusDays(7), today, null,
                null, null));
        assertThat(onlyB.totals().attempts()).isEqualTo(1);
        assertThat(onlyB.totals().successes()).isZero();
        ContactInsightsView asksA = asOperator(operatorZ, () -> intelligence.insights(today.minusDays(7), today,
                creditorA, null, null));
        assertThat(asksA.totals().attempts()).isZero();
        assertThat(asksA.heatmap()).isEmpty();
        // devedor só do credor A fica fora do perfil para quem só vê o credor B
        assertThatThrownBy(() -> asOperator(operatorZ, () -> intelligence.profile(a.debtorId(), null)))
                .isInstanceOf(ResourceNotFoundException.class);
        // caso de outro devedor não abre o perfil
        assertThatThrownBy(() -> asManager(manager, () -> intelligence.profile(a.debtorId(), b.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        // filtro por tipo de pessoa inexistente nos dados
        assertThat(asManager(manager, () -> intelligence.insights(today.minusDays(7), today, null, null, "COMPANY"))
                .totals().attempts()).isZero();
    }

    @Test
    void completedAgreementCountsAndBrokenDoesNot() {
        enableScoring();
        CaseView a = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        CaseView b = loadCase(creditorA, portfolioA, CPFS[1], "900.00", 20);
        Instant now = Instant.now();
        UUID phoneA = phoneOf(a.debtorId());
        publishAndAwait(call(a, phoneA, "OUTBOUND", "NO_ANSWER", 0, now.minusSeconds(9000)), "scoring.contact.call");
        publishAndAwait(call(a, phoneA, "OUTBOUND", "ANSWERED", 120, now.minusSeconds(8000)), "scoring.contact.call");
        publishAndAwait(call(b, phoneOf(b.debtorId()), "OUTBOUND", "ANSWERED", 60, now.minusSeconds(8000)),
                "scoring.contact.call");

        UUID kept = Ids.newId();
        UUID broken = Ids.newId();
        publishAndAwait(agreement(a, kept, "FORMALIZED", null, now.minusSeconds(7000)), "scoring.contact.agreement");
        publishAndAwait(agreement(a, kept, "SETTLED", "IN_PAYMENT", now.minusSeconds(100)), "scoring.contact.agreement");
        publishAndAwait(agreement(b, broken, "FORMALIZED", null, now.minusSeconds(7000)), "scoring.contact.agreement");
        publishAndAwait(agreement(b, broken, "BROKEN", "FORMALIZED", now.minusSeconds(50)), "scoring.contact.agreement");
        // evento atrasado (mais antigo que a situação gravada) não desfaz o cumprimento
        publishAndAwait(agreement(a, kept, "IN_PAYMENT", "FORMALIZED", now.minusSeconds(3000)),
                "scoring.contact.agreement");

        assertThat(count("select attempts_before from scoring.agreement_contact_outcome where agreement_id = ?", kept))
                .isEqualTo(2);
        LocalDate today = LocalDate.now(ZONE);
        ContactInsightsView view = asManager(manager, () -> intelligence.insights(today.minusDays(7), today, null,
                portfolioA, null));
        assertThat(view.totals().agreements()).isEqualTo(2);
        assertThat(view.totals().completed()).isEqualTo(1);
        assertThat(view.totals().broken()).isEqualTo(1);
        assertThat(view.totals().fulfillmentRate()).isEqualByComparingTo(new BigDecimal("0.5"));
        AgreementBucketView twoOrThree = view.agreementsByAttempts().stream().filter(x -> x.bucket().equals("2-3"))
                .findFirst().orElseThrow();
        assertThat(twoOrThree.completed()).isEqualTo(1);
        assertThat(twoOrThree.fulfillmentRate()).isEqualByComparingTo(BigDecimal.ONE);
        AgreementBucketView zeroOrOne = view.agreementsByAttempts().stream().filter(x -> x.bucket().equals("0-1"))
                .findFirst().orElseThrow();
        assertThat(zeroOrOne.broken()).isEqualTo(1);
        assertThat(zeroOrOne.fulfillmentRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.medianAttemptsToCompletedAgreement()).isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    void settledWinsOverInPaymentAtTheSameInstantInAnyOrder() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        Instant now = Instant.now();
        UUID agreementId = Ids.newId();
        publishAndAwait(agreement(c, agreementId, "FORMALIZED", null, now.minusSeconds(7000)),
                "scoring.contact.agreement");
        Instant paidAt = now.minusSeconds(100);
        // o mesmo pagamento: SETTLED chega antes do IN_PAYMENT, com o mesmo instante
        publishAndAwait(agreement(c, agreementId, "SETTLED", "IN_PAYMENT", paidAt), "scoring.contact.agreement");
        publishAndAwait(agreement(c, agreementId, "IN_PAYMENT", "FORMALIZED", paidAt), "scoring.contact.agreement");

        assertThat(count("select count(*) from scoring.agreement_contact_outcome where agreement_id = ? "
                + "and final_status = 'COMPLETED'", agreementId)).isEqualTo(1);
    }

    @Test
    void agreementFirstSeenAfterFormalizationIsNotTracked() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        double notTracked = ignored("agreement_not_tracked");
        UUID agreementId = Ids.newId();

        publishAndAwait(agreement(c, agreementId, "IN_PAYMENT", "FORMALIZED", Instant.now()),
                "scoring.contact.agreement");
        publishAndAwait(agreement(c, agreementId, "SETTLED", "IN_PAYMENT", Instant.now()), "scoring.contact.agreement");

        assertThat(count("select count(*) from scoring.agreement_contact_outcome")).isZero();
        assertThat(ignored("agreement_not_tracked")).isEqualTo(notTracked + 2);
    }

    @Test
    void anonymizationRemovesFactsAndKeepsOnlyUnidentifiedStatistics() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        UUID phone = phoneOf(c.debtorId());
        Instant now = Instant.now();
        publishAndAwait(call(c, phone, "OUTBOUND", "ANSWERED", 60, now.minusSeconds(600)), "scoring.contact.call");
        UUID agreementId = Ids.newId();
        publishAndAwait(agreement(c, agreementId, "FORMALIZED", null, now), "scoring.contact.agreement");

        publishAndAwait(new DebtorAnonymized(Ids.newId(), tenant, Instant.now(), c.debtorId()),
                "scoring.contact.debtor-anonymized");

        assertThat(count("select count(*) from scoring.contact_attempt where debtor_id = ?", c.debtorId())).isZero();
        assertThat(count("select count(*) from scoring.contact_profile where debtor_id = ?", c.debtorId())).isZero();
        assertThat(count("select count(*) from scoring.agreement_contact_outcome where debtor_id is null "
                + "and agreement_id is null and case_id is null and attempts_before = 1")).isEqualTo(1);
        // instantes truncados para o dia no fuso de operação: a linha não é religada ao acordo pelo instante
        assertThat(count("select count(*) from scoring.agreement_contact_outcome "
                + "where agreed_at = date_trunc('day', agreed_at, 'America/Sao_Paulo') "
                + "and status_at = date_trunc('day', status_at, 'America/Sao_Paulo') "
                + "and updated_at = date_trunc('day', updated_at, 'America/Sao_Paulo')")).isEqualTo(1);
    }

    @Test
    void retentionDiscardsOldFactsAndRebuildsTheProfile() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        UUID phone = phoneOf(c.debtorId());
        Instant now = Instant.now();
        publishAndAwait(call(c, phone, "OUTBOUND", "ANSWERED", 60, now.minus(Duration.ofDays(1200))),
                "scoring.contact.call");
        publishAndAwait(call(c, phone, "OUTBOUND", "NO_ANSWER", 0, now.minusSeconds(600)), "scoring.contact.call");
        assertThat(count("select attempts from scoring.contact_profile where debtor_id = ?", c.debtorId())).isEqualTo(2);

        // padrão do sistema: 1095 dias; rodadas em transações novas até acabar, como o agendamento
        assertThat(purgeContactFactsInRounds()).isEqualTo(1);
        assertThat(purgeContactFactsInRounds()).isZero();

        assertThat(count("select count(*) from scoring.contact_attempt where debtor_id = ?", c.debtorId())).isEqualTo(1);
        assertThat(count("select attempts from scoring.contact_profile where debtor_id = ?", c.debtorId())).isEqualTo(1);
        assertThat(count("select successes from scoring.contact_profile where debtor_id = ?", c.debtorId())).isZero();
    }

    private int purgeContactFactsInRounds() {
        int removed = 0;
        for (int i = 0; i < 10; i++) {
            ScoringTestHooks.RetentionRound round = system(() -> ScoringTestHooks.purgeContactFactsRound(
                    applicationContext));
            removed += round.removed();
            if (!round.more()) {
                return removed;
            }
        }
        throw new AssertionError("retenção não terminou em 10 rodadas");
    }

    private double ignored(String reason) {
        return meterRegistry.counter("scoring.contact_facts.ignored", "reason", reason).count();
    }

    private UUID phoneOf(UUID debtorId) {
        return system(() -> contacts.list(debtorId)).stream().filter(x -> x.type().equals("PHONE")).findFirst()
                .orElseThrow().id();
    }

    private CallCompleted call(CaseView c, UUID contactId, String direction, String result, int seconds, Instant at) {
        return new CallCompleted(Ids.newId(), tenant, at, Ids.newId(), c.id(), c.debtorId(), c.creditorId(), operatorX,
                direction, result, seconds, c.portfolioId(), contactId);
    }

    private InteractionRecorded interaction(CaseView c, UUID contactId, String direction, boolean effective,
                                            String source, Instant at) {
        return new InteractionRecorded(Ids.newId(), tenant, at, Ids.newId(), c.id(), c.debtorId(), c.creditorId(),
                "PHONE", "CPC", effective, operatorX, direction, c.portfolioId(), contactId, source);
    }

    private MessageStatusChanged message(CaseView c, UUID contactId, UUID messageId, String direction, String status,
                                         Instant at) {
        return new MessageStatusChanged(Ids.newId(), tenant, at, messageId, Ids.newId(), c.debtorId(), c.creditorId(),
                c.id(), null, "WHATSAPP", direction, status, "OUTBOUND".equals(direction) ? "USER" : null,
                "OUTBOUND".equals(direction) ? operatorX : null, c.portfolioId(), contactId);
    }

    private AgreementStatusChanged agreement(CaseView c, UUID agreementId, String status, String previous, Instant at) {
        return new AgreementStatusChanged(Ids.newId(), tenant, at, agreementId, c.id(), c.debtorId(), c.creditorId(),
                c.portfolioId(), List.of(), previous, status, new BigDecimal("500.00"), null);
    }
}
