package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contato recebido na carga: janela de novidade, uso e reenvio (docs/tecnico/desenho/contatos-recebidos.md). */
class DebtorContactTest {

    private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();

    private static DebtorContact phone(boolean whatsapp) {
        return new DebtorContact(UUID.randomUUID(), ContactValue.parse(ContactType.PHONE, "(11) 98765-4321"), "hash",
                ContactLabel.MOBILE, whatsapp, ContactSource.IMPORT, 0);
    }

    @Test
    void contactOfTheLoadThatCreatedTheDebtorIsNeverNew() {
        DebtorContact c = phone(false);
        c.receivedByImport(BATCH_1, false, null, NOW);
        assertThat(c.isReceivedAfterRegistration()).isFalse();
        assertThat(c.getNoveltyUntil()).isNull();
        assertThat(c.novelty(NOW)).isNull();
        assertThat(c.isNoveltyPending(NOW)).isFalse();
        assertThat(c.getFirstImportBatchId()).isEqualTo(BATCH_1);
        assertThat(c.getLastReceivedAt()).isEqualTo(NOW);
        assertThat(c.confirmedByCreditorAt()).isNull();
    }

    @Test
    void contactReceivedForExistingDebtorIsNewUntilTheWindowEnds() {
        DebtorContact c = phone(true);
        c.receivedByImport(BATCH_2, true, WINDOW, NOW);
        assertThat(c.getNoveltyUntil()).isEqualTo(NOW.plus(WINDOW));
        assertThat(c.novelty(NOW)).isEqualTo(ContactNovelty.NEW);
        assertThat(c.isNoveltyPending(NOW.plus(Duration.ofDays(29)))).isTrue();
        assertThat(c.novelty(NOW.plus(WINDOW))).isNull();
        assertThatThrownBy(() -> c.receivedByImport(BATCH_2, true, WINDOW, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> phone(false).receivedByImport(BATCH_2, true, null, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void firstUseEndsTheNoveltyAndKeepsTheResultUntilTheWindowEnds() {
        DebtorContact noAnswer = phone(false);
        noAnswer.receivedByImport(BATCH_2, true, WINDOW, NOW);
        assertThat(noAnswer.recordUse(NOW.plusSeconds(60), false)).isTrue();
        assertThat(noAnswer.novelty(NOW.plusSeconds(120))).isEqualTo(ContactNovelty.NEW_NO_ANSWER);
        assertThat(noAnswer.isNoveltyPending(NOW.plusSeconds(120))).isFalse();
        // só a primeira tentativa conta; o primeiro contato efetivo é gravado uma vez
        assertThat(noAnswer.recordUse(NOW.plusSeconds(600), false)).isFalse();
        assertThat(noAnswer.getFirstAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(noAnswer.recordUse(NOW.plusSeconds(900), true)).isTrue();
        assertThat(noAnswer.getFirstEffectiveAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(noAnswer.novelty(NOW.plusSeconds(1000))).isEqualTo(ContactNovelty.NEW_ANSWERED);
        assertThat(noAnswer.recordUse(NOW.plusSeconds(1200), true)).isFalse();
        assertThat(noAnswer.novelty(NOW.plus(WINDOW))).isNull();
    }

    @Test
    void responseWithoutPreviousAttemptCountsAsAttemptAndEffective() {
        DebtorContact c = phone(false);
        c.receivedByImport(BATCH_2, true, WINDOW, NOW);
        assertThat(c.recordUse(NOW.plusSeconds(30), true)).isTrue();
        assertThat(c.getFirstAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(c.novelty(NOW.plusSeconds(60))).isEqualTo(ContactNovelty.NEW_ANSWERED);
    }

    @Test
    void invalidContactLosesTheBadge() {
        DebtorContact c = phone(false);
        c.receivedByImport(BATCH_2, true, WINDOW, NOW);
        c.markInvalid("Número não existe", NOW.plusSeconds(10));
        assertThat(c.novelty(NOW.plusSeconds(20))).isNull();
        assertThat(c.isNoveltyPending(NOW.plusSeconds(20))).isFalse();
        c.markValid(NOW.plusSeconds(30));
        assertThat(c.novelty(NOW.plusSeconds(40))).isEqualTo(ContactNovelty.NEW);
    }

    @Test
    void resendConfirmsTheContactOncePerLoad() {
        DebtorContact c = phone(false);
        c.receivedByImport(BATCH_1, false, null, NOW);
        assertThat(c.resentByImport(BATCH_1, NOW.plusSeconds(60))).isFalse();
        assertThat(c.confirmedByCreditorAt()).isNull();
        Instant later = NOW.plus(Duration.ofDays(31));
        assertThat(c.resentByImport(BATCH_2, later)).isTrue();
        assertThat(c.resentByImport(BATCH_2, later.plusSeconds(5))).isFalse();
        assertThat(c.getLastImportBatchId()).isEqualTo(BATCH_2);
        assertThat(c.getLastReceivedAt()).isEqualTo(later);
        assertThat(c.confirmedByCreditorAt()).isEqualTo(later);
        // reenvio não torna o contato novo
        assertThat(c.novelty(later)).isNull();
    }

    @Test
    void manualContactResentByALoadIsConfirmedWithoutBeingNew() {
        DebtorContact manual = new DebtorContact(UUID.randomUUID(), ContactValue.parse(ContactType.EMAIL, "maria@exemplo.com"),
                "hash", ContactLabel.OTHER, false, ContactSource.MANUAL, 0);
        assertThat(manual.resentByImport(BATCH_2, NOW)).isTrue();
        assertThat(manual.confirmedByCreditorAt()).isEqualTo(NOW);
        assertThat(manual.receivedAt()).isNull();
        assertThat(manual.novelty(NOW)).isNull();
    }

    @Test
    void settingsWindowIsBetweenOneAndOneHundredEightyDays() {
        assertThat(new ContactNoveltySettings(1).window()).isEqualTo(Duration.ofDays(1));
        assertThat(new ContactNoveltySettings(180).getWindowDays()).isEqualTo(180);
        assertThatThrownBy(() -> new ContactNoveltySettings(0)).hasFieldOrPropertyWithValue("code",
                "contact_novelty.window_invalid");
        assertThatThrownBy(() -> new ContactNoveltySettings(181)).isInstanceOf(BusinessRuleException.class);
        ContactNoveltySettings s = new ContactNoveltySettings(30);
        assertThat(s.change(30)).isFalse();
        assertThat(s.change(45)).isTrue();
    }
}
