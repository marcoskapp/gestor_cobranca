package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discador: número novo recebido na carga ainda sem tentativa vem primeiro e, depois, o rodízio segue o último discado
 * na ordem de prioridade (contatos-recebidos.md §4 e §9).
 */
class NextPhoneTest {

    private static ChannelContact phone(int priority, boolean newPending) {
        return new ChannelContact(UUID.randomUUID(), "PHONE", "+55119999900" + priority, "MOBILE", false, priority,
                "UNVERIFIED", newPending);
    }

    /** Mesmo contato depois da primeira tentativa: a novidade acabou. */
    private static ChannelContact attempted(ChannelContact c) {
        return new ChannelContact(c.contactId(), c.type(), c.value(), c.label(), c.whatsapp(), c.priority(), c.status(),
                false);
    }

    @Test
    void firstAttemptGoesToTheHighestPriorityPhone() {
        ChannelContact p0 = phone(0, false);
        ChannelContact p1 = phone(1, false);
        assertThat(DialerTickJob.nextPhone(List.of(p0, p1), null)).isEqualTo(p0);
    }

    @Test
    void newPhoneComesFirstAndThenRotationResumesAtTheHighestPriority() {
        ChannelContact p0 = phone(0, false);
        ChannelContact p1 = phone(1, false);
        ChannelContact n = phone(2, true);

        // tentativa 1: o número novo pendente
        ChannelContact first = DialerTickJob.nextPhone(List.of(p0, p1, n), null);
        assertThat(first).isEqualTo(n);

        // tentativa 2: a novidade acabou; o seguinte ao último discado é P0 (não pula o de maior prioridade)
        List<ChannelContact> afterNovelty = List.of(p0, p1, attempted(n));
        ChannelContact second = DialerTickJob.nextPhone(afterNovelty, first.contactId());
        assertThat(second).isEqualTo(p0);

        // tentativas seguintes: rodízio pela prioridade
        assertThat(DialerTickJob.nextPhone(afterNovelty, p0.contactId())).isEqualTo(p1);
        assertThat(DialerTickJob.nextPhone(afterNovelty, p1.contactId()).contactId()).isEqualTo(n.contactId());
    }

    @Test
    void newPendingPhonesFollowPriorityWithoutRepeatingTheLastDialed() {
        ChannelContact p0 = phone(0, false);
        ChannelContact newer = phone(1, true);
        ChannelContact newest = phone(2, true);
        List<ChannelContact> phones = List.of(p0, newer, newest);
        assertThat(DialerTickJob.nextPhone(phones, null)).isEqualTo(newer);
        // a tentativa por "newer" ainda não encerrou a novidade dele (uso não registrado): não repete
        assertThat(DialerTickJob.nextPhone(phones, newer.contactId())).isEqualTo(newest);
    }

    @Test
    void lastDialedOutOfTheListRestartsAtTheFirst() {
        ChannelContact p0 = phone(0, false);
        ChannelContact p1 = phone(1, false);
        assertThat(DialerTickJob.nextPhone(List.of(p0, p1), UUID.randomUUID())).isEqualTo(p0);
    }

    @Test
    void singlePhoneIsAlwaysChosen() {
        ChannelContact p0 = phone(0, false);
        assertThat(DialerTickJob.nextPhone(List.of(p0), p0.contactId())).isEqualTo(p0);
    }
}
