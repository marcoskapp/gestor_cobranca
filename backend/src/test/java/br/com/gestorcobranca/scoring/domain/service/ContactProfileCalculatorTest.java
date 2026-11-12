package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactFact;
import br.com.gestorcobranca.scoring.domain.model.ContactFact.Direction;
import br.com.gestorcobranca.scoring.domain.model.ContactFact.Kind;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow.SlotCount;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.Confidence;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.Evaluation;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.Prior;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.RateBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContactProfileCalculatorTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final UUID DEBTOR = UUID.randomUUID();
    private static final UUID CREDITOR_A = UUID.randomUUID();
    private static final UUID CREDITOR_B = UUID.randomUUID();
    private static final UUID PHONE_1 = UUID.randomUUID();
    private static final UUID PHONE_2 = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-11-02T13:00:00Z"); // segunda, 10h em São Paulo

    @Test
    void smoothingKeepsOneOfOneAwayFromCertainty() {
        Prior prior = Prior.ofRate(0.3);

        assertThat(prior.alpha()).isCloseTo(3.0, within(1e-9));
        assertThat(prior.beta()).isCloseTo(7.0, within(1e-9));
        assertThat(prior.smooth(1, 1)).isCloseTo(4.0 / 11, within(1e-9));
        assertThat(prior.smooth(0, 0)).isCloseTo(0.3, within(1e-9));
        // com muitas observações a taxa observada domina
        assertThat(prior.smooth(900, 1000)).isCloseTo(903.0 / 1010, within(1e-9));
    }

    @Test
    void priorComesFromThePortfolioWithEnoughAttemptsThenTheAgencyThenNeutral() {
        RateBase portfolio = new RateBase(40, 20);
        RateBase tenantChannel = new RateBase(100, 10);
        RateBase tenantAll = new RateBase(500, 100);

        assertThat(ContactProfileCalculator.prior(portfolio, tenantChannel, tenantAll).mean()).isCloseTo(0.5, within(1e-9));
        assertThat(ContactProfileCalculator.prior(new RateBase(29, 29), tenantChannel, tenantAll).mean())
                .isCloseTo(0.1, within(1e-9));
        assertThat(ContactProfileCalculator.prior(null, new RateBase(5, 5), tenantAll).mean()).isCloseTo(0.2, within(1e-9));
        assertThat(ContactProfileCalculator.prior(null, null, new RateBase(10, 1)).mean())
                .isCloseTo(ContactProfileCalculator.NEUTRAL_RATE, within(1e-9));
    }

    @Test
    void confidenceByAttempts() {
        assertThat(ContactProfileCalculator.confidence(4)).isEqualTo(Confidence.LOW);
        assertThat(ContactProfileCalculator.confidence(5)).isEqualTo(Confidence.MEDIUM);
        assertThat(ContactProfileCalculator.confidence(19)).isEqualTo(Confidence.MEDIUM);
        assertThat(ContactProfileCalculator.confidence(20)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void hourAndWeekdayAreInTheOperationTimeZone() {
        // 23h30 UTC de domingo = 20h30 de domingo em São Paulo
        ContactFact f = ContactFact.of(UUID.randomUUID(), Kind.ATTEMPT, DEBTOR, CREDITOR_A, null, null, PHONE_1,
                ContactChannel.PHONE, Direction.OUTBOUND, true, null, Instant.parse("2026-11-08T23:30:00Z"), SP);

        assertThat(f.localHour()).isEqualTo(20);
        assertThat(f.localWeekday()).isEqualTo(7);
        // 02h UTC de segunda = 23h de domingo em São Paulo
        ContactFact late = ContactFact.of(UUID.randomUUID(), Kind.ATTEMPT, DEBTOR, CREDITOR_A, null, null, PHONE_1,
                ContactChannel.PHONE, Direction.OUTBOUND, false, null, Instant.parse("2026-11-09T02:00:00Z"), SP);
        assertThat(late.localHour()).isEqualTo(23);
        assertThat(late.localWeekday()).isEqualTo(7);
    }

    @Test
    void aggregatesBySeriesWithAttemptsUntilTheFirstSuccessAndSlots() {
        List<ContactFact> facts = new ArrayList<>();
        facts.add(attempt(PHONE_1, 0, false));      // 10h
        facts.add(attempt(PHONE_1, 1, false));      // 11h
        facts.add(attempt(PHONE_1, 9, true));       // 19h → 3ª tentativa, 1º sucesso
        facts.add(attempt(PHONE_1, 10, true));      // 20h
        facts.add(response(PHONE_1, 12));           // 22h, devedor ligou
        facts.add(fact(Kind.READ, CREDITOR_A, null, ContactChannel.WHATSAPP, Direction.OUTBOUND, false, 0));
        facts.add(fact(Kind.ATTEMPT, CREDITOR_B, PHONE_1, ContactChannel.PHONE, Direction.OUTBOUND, false, 2));
        Collections.shuffle(facts);

        List<ContactProfileRow> rows = ContactProfileCalculator.aggregate(DEBTOR, facts);

        assertThat(rows).hasSize(3);
        ContactProfileRow a = rows.stream().filter(r -> CREDITOR_A.equals(r.creditorId())
                && r.channel() == ContactChannel.PHONE).findFirst().orElseThrow();
        assertThat(a.attempts()).isEqualTo(4);
        assertThat(a.successes()).isEqualTo(2);
        assertThat(a.debtorInitiated()).isEqualTo(1);
        assertThat(a.attemptsToFirstSuccess()).isEqualTo(3);
        assertThat(a.lastSuccessAt()).isEqualTo(BASE.plusSeconds(12 * 3600));
        assertThat(a.slots().get(1).attempts()).isEqualTo(2);  // 09–12
        assertThat(a.slots().get(4).successes()).isEqualTo(2); // 18–21
        ContactProfileRow reads = rows.stream().filter(r -> r.channel() == ContactChannel.WHATSAPP).findFirst()
                .orElseThrow();
        assertThat(reads.reads()).isEqualTo(1);
        assertThat(reads.successes()).isZero();

        List<ContactProfileRow> merged = ContactProfileCalculator.merge(rows);
        ContactProfileRow phone = merged.stream().filter(r -> r.channel() == ContactChannel.PHONE).findFirst()
                .orElseThrow();
        assertThat(phone.attempts()).isEqualTo(5);
        assertThat(phone.attemptsToFirstSuccess()).isEqualTo(3);
        assertThat(phone.creditorId()).isNull();
    }

    @Test
    void bestSlotNeedsThreeAttemptsAndTiesGoToTheMostRecent() {
        Prior prior = Prior.ofRate(0.3);
        Instant old = BASE;
        Instant recent = BASE.plusSeconds(86_400);
        List<SlotCount> slots = List.of(new SlotCount(2, 2, recent), new SlotCount(4, 2, old),
                SlotCount.EMPTY, SlotCount.EMPTY, new SlotCount(4, 2, recent), SlotCount.EMPTY);

        // a faixa 06–09 tem 2 de 2, mas não tem tentativas suficientes; 09–12 e 18–21 empatam → a mais recente
        assertThat(ContactProfileCalculator.bestSlot(slots, prior)).isEqualTo(4);
        assertThat(ContactProfileCalculator.bestSlot(List.of(new SlotCount(2, 2, recent), SlotCount.EMPTY,
                SlotCount.EMPTY, SlotCount.EMPTY, SlotCount.EMPTY, SlotCount.EMPTY), prior)).isNull();
        List<SlotCount> better = List.of(SlotCount.EMPTY, new SlotCount(5, 4, old), SlotCount.EMPTY, SlotCount.EMPTY,
                new SlotCount(4, 2, recent), SlotCount.EMPTY);
        assertThat(ContactProfileCalculator.bestSlot(better, prior)).isEqualTo(1);
    }

    @Test
    void contactsAreOrderedBySmoothedRateAndPreferredChannelWeighsDebtorInitiatedContact() {
        ContactProfileRow phone1 = row(PHONE_1, ContactChannel.PHONE, 10, 6, 0);
        ContactProfileRow phone2 = row(PHONE_2, ContactChannel.PHONE, 1, 1, 0);
        ContactProfileRow whatsapp = row(PHONE_1, ContactChannel.WHATSAPP, 4, 1, 2);

        Evaluation e = ContactProfileCalculator.evaluate(List.of(phone2, whatsapp, phone1), c -> Prior.ofRate(0.3));

        // 6 de 10 (0,45) fica à frente de 1 de 1 (0,36): a suavização não deixa uma tentativa virar certeza
        assertThat(e.contacts()).extracting(x -> x.row().contactId(), x -> x.row().channel())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PHONE_1, ContactChannel.PHONE),
                        org.assertj.core.groups.Tuple.tuple(PHONE_2, ContactChannel.PHONE),
                        org.assertj.core.groups.Tuple.tuple(PHONE_1, ContactChannel.WHATSAPP));
        assertThat(e.contacts().getFirst().rate()).isCloseTo(9.0 / 20, within(1e-9));
        assertThat(e.contacts().get(1).rate()).isCloseTo(4.0 / 11, within(1e-9));
        assertThat(e.contacts().getFirst().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(e.contacts().get(1).confidence()).isEqualTo(Confidence.LOW);
        // preferência: ligação (7 + 3) / (11 + 10) = 0,476; WhatsApp com peso 2 para o contato do devedor
        // (1 + 2·2 + 3) / (4 + 2·2 + 10) = 0,444
        assertThat(e.channels()).extracting(ContactProfileCalculator.ChannelEvaluation::channel)
                .containsExactly(ContactChannel.PHONE, ContactChannel.WHATSAPP);
        assertThat(e.channels().get(1).preference()).isCloseTo(8.0 / 18, within(1e-9));
        assertThat(e.preferred()).isEqualTo(ContactChannel.PHONE);
        assertThat(e.totalResponses()).isEqualTo(10);
        assertThat(e.totalAttempts()).isEqualTo(15);

        // com o devedor iniciando mais contatos pelo WhatsApp, ele passa a ser o preferido
        ContactProfileRow whatsappActive = row(PHONE_1, ContactChannel.WHATSAPP, 4, 2, 5);
        assertThat(ContactProfileCalculator.evaluate(List.of(phone1, whatsappActive), c -> Prior.ofRate(0.3))
                .preferred()).isEqualTo(ContactChannel.WHATSAPP);
    }

    @Test
    void preferredChannelNeedsTwoResponsesAndNoTie() {
        Evaluation one = ContactProfileCalculator.evaluate(List.of(row(PHONE_1, ContactChannel.PHONE, 3, 1, 0)),
                c -> Prior.ofRate(0.3));
        assertThat(one.preferred()).isNull();
        assertThat(one.totalResponses()).isEqualTo(1);

        Evaluation tie = ContactProfileCalculator.evaluate(List.of(row(PHONE_1, ContactChannel.PHONE, 4, 2, 0),
                row(PHONE_2, ContactChannel.SMS, 4, 2, 0)), c -> Prior.ofRate(0.3));
        assertThat(tie.preferred()).isNull();
    }

    @Test
    void messageReceivedWithinTheReplyWindowIsAnAnswerNotDebtorInitiated() {
        List<ContactFact> facts = new ArrayList<>();
        facts.add(fact(Kind.ATTEMPT, CREDITOR_A, PHONE_1, ContactChannel.WHATSAPP, Direction.OUTBOUND, true, 0));
        facts.add(message(PHONE_1, 71)); // responde à tentativa (dentro de 72 h)

        ContactProfileRow row = ContactProfileCalculator.aggregate(DEBTOR, facts).getFirst();

        assertThat(row.successes()).isEqualTo(1);
        assertThat(row.debtorInitiated()).isZero();
        assertThat(row.lastSuccessAt()).isEqualTo(BASE.plusSeconds(71 * 3600L));

        // passada a janela, a mensagem do devedor é contato iniciado por ele
        List<ContactFact> late = new ArrayList<>(List.of(facts.getFirst()));
        late.add(message(PHONE_1, 73));
        assertThat(ContactProfileCalculator.aggregate(DEBTOR, late).getFirst().debtorInitiated()).isEqualTo(1);
    }

    @Test
    void threeMessagesInARowAnswerASingleAttempt() {
        List<ContactFact> facts = new ArrayList<>();
        // duas enviadas; a resposta marcou só a mais recente (ContactFactStore#markLatestAttemptAnswered)
        facts.add(fact(Kind.ATTEMPT, CREDITOR_A, PHONE_1, ContactChannel.WHATSAPP, Direction.OUTBOUND, false, 0));
        facts.add(fact(Kind.ATTEMPT, CREDITOR_A, PHONE_1, ContactChannel.WHATSAPP, Direction.OUTBOUND, true, 1));
        facts.add(message(PHONE_1, 2));
        facts.add(message(PHONE_1, 3));
        facts.add(message(PHONE_1, 4)); // continua a mesma conversa
        Collections.shuffle(facts);

        ContactProfileRow row = ContactProfileCalculator.aggregate(DEBTOR, facts).getFirst();

        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.successes()).isEqualTo(1);
        assertThat(row.debtorInitiated()).isZero();
        assertThat(row.attemptsToFirstSuccess()).isEqualTo(2);
    }

    @Test
    void messageStartingAConversationAndIncomingCallsAreDebtorInitiated() {
        List<ContactFact> facts = new ArrayList<>();
        // sem tentativa recente: o devedor puxou a conversa (a 2ª mensagem continua a mesma conversa)
        facts.add(message(PHONE_1, 0));
        facts.add(message(PHONE_1, 1));
        // ligação recebida conta como iniciada mesmo logo depois de uma tentativa
        facts.add(attempt(PHONE_1, 2, false));
        facts.add(response(PHONE_1, 3));
        // tentativa de outro credor não torna a mensagem do credor A uma resposta
        facts.add(fact(Kind.ATTEMPT, CREDITOR_B, PHONE_1, ContactChannel.WHATSAPP, Direction.OUTBOUND, false, 100));
        facts.add(message(PHONE_1, 101));

        List<ContactProfileRow> rows = ContactProfileCalculator.aggregate(DEBTOR, facts);

        ContactProfileRow whatsapp = rows.stream().filter(r -> CREDITOR_A.equals(r.creditorId())
                && r.channel() == ContactChannel.WHATSAPP).findFirst().orElseThrow();
        assertThat(whatsapp.debtorInitiated()).isEqualTo(2);
        ContactProfileRow calls = rows.stream().filter(r -> r.channel() == ContactChannel.PHONE).findFirst()
                .orElseThrow();
        assertThat(calls.debtorInitiated()).isEqualTo(1);
    }

    @Test
    void weightedMedians() {
        assertThat(ContactProfileCalculator.weightedMedian(Map.of(1, 2L, 5, 1L))).isEqualTo(1.0);
        assertThat(ContactProfileCalculator.weightedMedian(Map.of(1, 2L, 5, 2L))).isEqualTo(3.0);
        assertThat(ContactProfileCalculator.weightedMedian(Map.of(7, 1L))).isEqualTo(7.0);
        assertThat(ContactProfileCalculator.weightedMedian(Map.of())).isNull();
    }

    private static ContactProfileRow row(UUID contact, ContactChannel channel, int attempts, int successes,
                                         int initiated) {
        List<SlotCount> slots = new ArrayList<>(Collections.nCopies(6, SlotCount.EMPTY));
        return new ContactProfileRow(DEBTOR, null, contact, channel, attempts, successes, initiated, 0,
                successes > 0 ? 1 : null, null, null, slots);
    }

    private static ContactFact attempt(UUID contact, int hoursAfterBase, boolean success) {
        return fact(Kind.ATTEMPT, CREDITOR_A, contact, ContactChannel.PHONE, Direction.OUTBOUND, success, hoursAfterBase);
    }

    private static ContactFact response(UUID contact, int hoursAfterBase) {
        return fact(Kind.RESPONSE, CREDITOR_A, contact, ContactChannel.PHONE, Direction.INBOUND, true, hoursAfterBase);
    }

    private static ContactFact message(UUID contact, int hoursAfterBase) {
        return fact(Kind.RESPONSE, CREDITOR_A, contact, ContactChannel.WHATSAPP, Direction.INBOUND, true,
                hoursAfterBase);
    }

    private static ContactFact fact(Kind kind, UUID creditor, UUID contact, ContactChannel channel, Direction direction,
                                    boolean success, int hoursAfterBase) {
        return ContactFact.of(UUID.randomUUID(), kind, DEBTOR, creditor, null, null, contact, channel, direction,
                success, "INDIVIDUAL", BASE.plusSeconds(hoursAfterBase * 3600L), SP);
    }
}
