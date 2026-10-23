package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.ChannelRule;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;
import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.ContactValueRef;
import br.com.gestorcobranca.compliance.domain.model.ContactValueType;
import br.com.gestorcobranca.compliance.domain.model.ContactWindow;
import br.com.gestorcobranca.compliance.domain.model.DenialReason;
import br.com.gestorcobranca.compliance.domain.model.RestrictionSource;
import br.com.gestorcobranca.compliance.domain.model.RestrictionType;
import br.com.gestorcobranca.compliance.domain.service.ContactRules.Decision;
import br.com.gestorcobranca.compliance.domain.service.ContactRules.Situation;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContactRulesTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final ContactRules RULES = new ContactRules(SP);
    private static final UUID DEBTOR = UUID.randomUUID();
    private static final UUID CREDITOR = UUID.randomUUID();
    private static final ContactWindow BUSINESS = new ContactWindow(LocalTime.of(8, 0), LocalTime.of(20, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
    // 2026-10-21 é quarta-feira
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);

    private static Instant local(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(SP).toInstant();
    }

    private static Situation situation(Instant at, ChannelRule rule, ContactWindow window, Set<LocalDate> holidays,
                                       Collection<Instant> attempts, ContactRestriction... restrictions) {
        return new Situation(DEBTOR, CREDITOR, ContactChannel.WHATSAPP, null, at, false, List.of(restrictions), rule,
                window, holidays, attempts);
    }

    private static ChannelRule limits(Integer perDay, Integer perWeek, Integer intervalMinutes, boolean holidays) {
        return new ChannelRule(ContactChannel.WHATSAPP, null, perDay, perWeek, intervalMinutes, holidays);
    }

    private static ContactRestriction restriction(UUID creditor, ContactChannel channel, Instant start, Instant end) {
        return new ContactRestriction(DEBTOR, creditor, channel, RestrictionType.DEBTOR_REQUEST, null, "pedido",
                RestrictionSource.DEBTOR, start, end, null);
    }

    @Test
    void allowedInsideWindowWithoutRule() {
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), null, BUSINESS, Set.of(), List.of()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.reasons()).isEmpty();
        assertThat(d.nextAllowedAt()).isNull();
    }

    @Test
    void outsideWindowPointsToNextOpeningInBusinessZone() {
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 21, 30), null, BUSINESS, Set.of(), List.of()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasons()).containsExactly(DenialReason.OUTSIDE_WINDOW);
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(1), 8, 0));
        // a janela é do fuso de operação: 22h30 UTC = 19h30 em São Paulo (dentro); 23h30 UTC = 20h30 (fora)
        Instant utcEvening = Instant.parse("2026-10-21T22:30:00Z");
        assertThat(RULES.evaluate(situation(utcEvening, null, BUSINESS, Set.of(), List.of())).allowed()).isTrue();
        assertThat(RULES.evaluate(situation(Instant.parse("2026-10-21T23:30:00Z"), null, BUSINESS, Set.of(), List.of()))
                .reasons()).containsExactly(DenialReason.OUTSIDE_WINDOW);
    }

    @Test
    void sundayIsSkippedToMonday() {
        LocalDate saturday = WEDNESDAY.plusDays(3);
        Decision d = RULES.evaluate(situation(local(saturday, 20, 15), null, BUSINESS, Set.of(), List.of()));
        assertThat(d.nextAllowedAt()).isEqualTo(local(saturday.plusDays(2), 8, 0));
    }

    @Test
    void windowCrossingMidnight() {
        ContactWindow night = new ContactWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), EnumSet.of(DayOfWeek.WEDNESDAY));
        assertThat(RULES.evaluate(situation(local(WEDNESDAY.plusDays(1), 1, 0), null, night, Set.of(), List.of()))
                .allowed()).isTrue();
        Decision d = RULES.evaluate(situation(local(WEDNESDAY.plusDays(1), 3, 0), null, night, Set.of(), List.of()));
        assertThat(d.reasons()).containsExactly(DenialReason.OUTSIDE_WINDOW);
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(7), 22, 0));
    }

    @Test
    void holidayBlocksOnlyWhenTheRuleSaysSo() {
        Set<LocalDate> holidays = Set.of(WEDNESDAY);
        Decision blocked = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), limits(null, null, null, true), BUSINESS,
                holidays, List.of()));
        assertThat(blocked.reasons()).containsExactly(DenialReason.HOLIDAY);
        assertThat(blocked.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(1), 8, 0));

        assertThat(RULES.evaluate(situation(local(WEDNESDAY, 10, 0), limits(null, null, null, false), BUSINESS, holidays,
                List.of())).allowed()).isTrue();
    }

    @Test
    void consecutiveHolidaysAreChained() {
        Set<LocalDate> holidays = Set.of(WEDNESDAY, WEDNESDAY.plusDays(1));
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), limits(null, null, null, true), BUSINESS, holidays,
                List.of()));
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(2), 8, 0));
    }

    @Test
    void dailyLimitCountsTheLocalDay() {
        ChannelRule rule = limits(2, null, null, false);
        List<Instant> attempts = List.of(local(WEDNESDAY, 8, 30), local(WEDNESDAY, 9, 0),
                local(WEDNESDAY.minusDays(1), 19, 0));
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), rule, BUSINESS, Set.of(), attempts));
        assertThat(d.reasons()).containsExactly(DenialReason.DAILY_LIMIT);
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(1), 8, 0));
        // tentativa de ontem à noite (horário local) não conta no dia de hoje
        assertThat(RULES.evaluate(situation(local(WEDNESDAY, 10, 0), rule, BUSINESS, Set.of(), attempts.subList(1, 3)))
                .allowed()).isTrue();
    }

    @Test
    void weeklyLimitCountsTheCalendarWeekFromMonday() {
        ChannelRule rule = limits(null, 3, null, false);
        LocalDate monday = WEDNESDAY.minusDays(2);
        List<Instant> attempts = List.of(local(monday, 9, 0), local(monday.plusDays(1), 9, 0), local(WEDNESDAY, 9, 0),
                local(monday.minusDays(1), 9, 0));
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 15, 0), rule, BUSINESS, Set.of(), attempts));
        assertThat(d.reasons()).containsExactly(DenialReason.WEEKLY_LIMIT);
        assertThat(d.nextAllowedAt()).isEqualTo(local(monday.plusWeeks(1), 8, 0));
    }

    @Test
    void minimumIntervalSinceLastAttempt() {
        ChannelRule rule = limits(null, null, 120, false);
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), rule, BUSINESS, Set.of(),
                List.of(local(WEDNESDAY, 9, 0))));
        assertThat(d.reasons()).containsExactly(DenialReason.MIN_INTERVAL);
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY, 11, 0));

        // intervalo terminando fora da janela: próximo instante é a próxima abertura
        Decision late = RULES.evaluate(situation(local(WEDNESDAY, 19, 0), rule, BUSINESS, Set.of(),
                List.of(local(WEDNESDAY, 18, 30))));
        assertThat(late.nextAllowedAt()).isEqualTo(local(WEDNESDAY.plusDays(1), 8, 0));
    }

    @Test
    void multipleReasonsAreReportedTogether() {
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), limits(1, 1, 600, true), BUSINESS,
                Set.of(WEDNESDAY), List.of(local(WEDNESDAY, 9, 0))));
        assertThat(d.reasons()).containsExactly(DenialReason.HOLIDAY, DenialReason.DAILY_LIMIT, DenialReason.WEEKLY_LIMIT,
                DenialReason.MIN_INTERVAL);
    }

    @Test
    void permanentRestrictionHasNoNextAllowedInstant() {
        ContactRestriction global = restriction(null, null, local(WEDNESDAY, 0, 0), null);
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), null, BUSINESS, Set.of(), List.of(), global));
        assertThat(d.reasons()).containsExactly(DenialReason.RESTRICTED_DEBTOR);
        assertThat(d.nextAllowedAt()).isNull();
    }

    @Test
    void temporaryRestrictionEndsAndExpiredOneIsIgnored() {
        ContactRestriction temporary = restriction(null, ContactChannel.WHATSAPP, local(WEDNESDAY, 0, 0),
                local(WEDNESDAY, 14, 0));
        Decision d = RULES.evaluate(situation(local(WEDNESDAY, 10, 0), null, BUSINESS, Set.of(), List.of(), temporary));
        assertThat(d.reasons()).containsExactly(DenialReason.RESTRICTED_CHANNEL);
        assertThat(d.nextAllowedAt()).isEqualTo(local(WEDNESDAY, 14, 0));

        assertThat(RULES.evaluate(situation(local(WEDNESDAY, 15, 0), null, BUSINESS, Set.of(), List.of(), temporary))
                .allowed()).isTrue();
    }

    @Test
    void restrictionOfAnotherCreditorOrChannelDoesNotApply() {
        ContactRestriction otherCreditor = restriction(UUID.randomUUID(), null, local(WEDNESDAY, 0, 0), null);
        ContactRestriction otherChannel = restriction(null, ContactChannel.EMAIL, local(WEDNESDAY, 0, 0), null);
        assertThat(RULES.evaluate(situation(local(WEDNESDAY, 10, 0), null, BUSINESS, Set.of(), List.of(), otherCreditor,
                otherChannel)).allowed()).isTrue();
    }

    @Test
    void contactValueRestrictionAppliesOnlyToThatValue() {
        String hash = "f".repeat(64);
        ContactRestriction byValue = new ContactRestriction(DEBTOR, null, null, RestrictionType.INVALID_CONTACT_HISTORY,
                new ContactValueRef(ContactValueType.PHONE, hash, "(11) *****-0000"), "não é do devedor",
                RestrictionSource.OPERATOR, local(WEDNESDAY, 0, 0), null, null);
        Situation withValue = new Situation(DEBTOR, CREDITOR, ContactChannel.WHATSAPP, hash, local(WEDNESDAY, 10, 0), false,
                List.of(byValue), null, BUSINESS, Set.of(), List.of());
        assertThat(RULES.evaluate(withValue).reasons()).containsExactly(DenialReason.RESTRICTED_CONTACT_VALUE);
        Situation otherValue = new Situation(DEBTOR, CREDITOR, ContactChannel.WHATSAPP, "e".repeat(64),
                local(WEDNESDAY, 10, 0), false, List.of(byValue), null, BUSINESS, Set.of(), List.of());
        assertThat(RULES.evaluate(otherValue).allowed()).isTrue();
    }

    @Test
    void anonymizedDebtorIsNeverContacted() {
        Situation s = new Situation(DEBTOR, CREDITOR, ContactChannel.EMAIL, null, local(WEDNESDAY, 10, 0), true, List.of(),
                null, BUSINESS, Set.of(), List.of());
        Decision d = RULES.evaluate(s);
        assertThat(d.reasons()).containsExactly(DenialReason.DEBTOR_ANONYMIZED);
        assertThat(d.nextAllowedAt()).isNull();
    }

    @Test
    void creditorPolicyOverridesTenantPolicyChannelByChannel() {
        Instant now = local(WEDNESDAY, 0, 0);
        ChannelRule tenantWhatsapp = new ChannelRule(ContactChannel.WHATSAPP, null, 5, null, null, false);
        ChannelRule tenantEmail = new ChannelRule(ContactChannel.EMAIL, null, 10, null, null, false);
        ChannelRule creditorWhatsapp = new ChannelRule(ContactChannel.WHATSAPP,
                new ContactWindow(LocalTime.of(9, 0), LocalTime.of(18, 0), EnumSet.of(DayOfWeek.WEDNESDAY)), 2, null, null,
                true);
        Optional<ContactPolicy> tenant = Optional.of(new ContactPolicy(null, 1, now, null, List.of(tenantWhatsapp, tenantEmail)));
        Optional<ContactPolicy> creditor = Optional.of(new ContactPolicy(CREDITOR, 1, now, null, List.of(creditorWhatsapp)));

        Optional<ChannelRule> whatsapp = ContactRules.effectiveRule(creditor, tenant, ContactChannel.WHATSAPP);
        assertThat(whatsapp).containsSame(creditorWhatsapp);
        assertThat(ContactRules.effectiveRule(creditor, tenant, ContactChannel.EMAIL)).containsSame(tenantEmail);
        assertThat(ContactRules.effectiveRule(creditor, tenant, ContactChannel.SMS)).isEmpty();
        assertThat(ContactRules.effectiveRule(Optional.empty(), tenant, ContactChannel.WHATSAPP)).containsSame(tenantWhatsapp);

        assertThat(ContactRules.effectiveWindow(whatsapp, BUSINESS).start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(ContactRules.effectiveWindow(Optional.of(tenantEmail), BUSINESS)).isEqualTo(BUSINESS);
    }

    @Test
    void startOfWeekIsMondayMidnightInBusinessZone() {
        assertThat(RULES.startOfWeek(local(WEDNESDAY, 10, 0))).isEqualTo(local(WEDNESDAY.minusDays(2), 0, 0));
    }
}
