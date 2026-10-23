package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactRestrictionTest {

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final UUID DEBTOR = UUID.randomUUID();
    static final UUID OTHER_DEBTOR = UUID.randomUUID();
    static final UUID CREDITOR_A = UUID.randomUUID();
    static final UUID CREDITOR_B = UUID.randomUUID();
    static final ContactValueRef PHONE = new ContactValueRef(ContactValueType.PHONE, "a".repeat(64), "(11) *****-4321");

    static ContactRestriction restriction(UUID debtor, UUID creditor, ContactChannel channel, RestrictionType type,
                                          ContactValueRef contact, Instant endsAt) {
        return new ContactRestriction(debtor, creditor, channel, type, contact, "motivo", RestrictionSource.OPERATOR, T0,
                endsAt, null);
    }

    @Test
    void globalRestrictionCoversEveryChannelAndCreditorOfTheDebtorOnly() {
        ContactRestriction r = restriction(DEBTOR, null, null, RestrictionType.LEGAL_BLOCK, null, null);
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.PHONE, null)).isTrue();
        assertThat(r.covers(DEBTOR, CREDITOR_B, ContactChannel.EMAIL, "x")).isTrue();
        assertThat(r.covers(OTHER_DEBTOR, CREDITOR_A, ContactChannel.PHONE, null)).isFalse();
        assertThat(r.denialReason()).isEqualTo(DenialReason.RESTRICTED_DEBTOR);
    }

    @Test
    void channelAndCreditorScopedRestriction() {
        ContactRestriction r = restriction(DEBTOR, CREDITOR_A, ContactChannel.WHATSAPP, RestrictionType.CREDITOR_REQUEST,
                null, null);
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.WHATSAPP, null)).isTrue();
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.SMS, null)).isFalse();
        assertThat(r.covers(DEBTOR, CREDITOR_B, ContactChannel.WHATSAPP, null)).isFalse();
        assertThat(r.denialReason()).isEqualTo(DenialReason.RESTRICTED_CHANNEL);
    }

    @Test
    void contactValueRestrictionOnlyCoversThatValueOnChannelsThatUseIt() {
        ContactRestriction r = restriction(DEBTOR, null, null, RestrictionType.INVALID_CONTACT_HISTORY, PHONE, null);
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.SMS, PHONE.hash())).isTrue();
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.PHONE, PHONE.hash())).isTrue();
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.SMS, "b".repeat(64))).isFalse();
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.SMS, null)).isFalse();
        assertThat(r.covers(DEBTOR, CREDITOR_A, ContactChannel.EMAIL, PHONE.hash())).isFalse();
        // histórico inválido é do devedor: outro devedor com o mesmo número não é alcançado
        assertThat(r.covers(OTHER_DEBTOR, CREDITOR_A, ContactChannel.SMS, PHONE.hash())).isFalse();
        assertThat(r.denialReason()).isEqualTo(DenialReason.RESTRICTED_CONTACT_VALUE);
    }

    @Test
    void optOutOfAContactValueCoversAnyDebtorUsingIt() {
        ContactRestriction r = new ContactRestriction(DEBTOR, null, ContactChannel.WHATSAPP, RestrictionType.OPT_OUT, PHONE,
                "SAIR", RestrictionSource.PROVIDER_STOP_KEYWORD, T0, null, null);
        assertThat(r.covers(OTHER_DEBTOR, CREDITOR_A, ContactChannel.WHATSAPP, PHONE.hash())).isTrue();
        assertThat(r.covers(OTHER_DEBTOR, CREDITOR_A, ContactChannel.SMS, PHONE.hash())).isFalse();
        assertThat(r.isOptOutOf(PHONE.hash(), ContactChannel.WHATSAPP, T0)).isTrue();
    }

    @Test
    void temporaryRestrictionStopsBeingEffectiveAtTheEnd() {
        Instant end = T0.plusSeconds(3600);
        ContactRestriction r = restriction(DEBTOR, null, null, RestrictionType.DEBTOR_REQUEST, null, end);
        assertThat(r.isEffectiveAt(T0.minusSeconds(1))).isFalse();
        assertThat(r.isEffectiveAt(T0)).isTrue();
        assertThat(r.isEffectiveAt(end.minusSeconds(1))).isTrue();
        assertThat(r.isEffectiveAt(end)).isFalse();
        assertThat(r.expiredUnnotified(end)).isTrue();
        r.markExpiryNotified(end);
        assertThat(r.expiredUnnotified(end)).isFalse();
    }

    @Test
    void revocationRequiresReasonAndHappensOnce() {
        ContactRestriction r = restriction(DEBTOR, null, null, RestrictionType.DEBTOR_REQUEST, null, null);
        assertThatThrownBy(() -> r.revoke(" ", UUID.randomUUID(), T0)).isInstanceOf(BusinessRuleException.class);
        r.revoke("pedido retirado", UUID.randomUUID(), T0.plusSeconds(10));
        assertThat(r.getStatus()).isEqualTo(RestrictionStatus.REVOKED);
        assertThat(r.isEffectiveAt(T0.plusSeconds(20))).isFalse();
        assertThatThrownBy(() -> r.revoke("de novo", UUID.randomUUID(), T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void invariants() {
        assertThatThrownBy(() -> restriction(null, null, null, RestrictionType.LEGAL_BLOCK, null, null))
                .hasFieldOrPropertyWithValue("code", "restriction.target_required");
        assertThatThrownBy(() -> restriction(DEBTOR, null, ContactChannel.EMAIL, RestrictionType.LEGAL_BLOCK, PHONE, null))
                .hasFieldOrPropertyWithValue("code", "restriction.channel_contact_mismatch");
        assertThatThrownBy(() -> restriction(DEBTOR, null, null, RestrictionType.LEGAL_BLOCK, null, T0))
                .hasFieldOrPropertyWithValue("code", "restriction.period_invalid");
    }
}
