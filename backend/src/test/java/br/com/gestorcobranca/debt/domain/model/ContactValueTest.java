package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactValueTest {

    @ParameterizedTest
    @ValueSource(strings = {"(11) 98765-4321", "11987654321", "+55 11 98765-4321", "5511987654321", "011 98765 4321"})
    void normalizesBrazilianMobileToE164(String raw) {
        ContactValue value = ContactValue.parse(ContactType.PHONE, raw);
        assertThat(value.value()).isEqualTo("+5511987654321");
        assertThat(value.isMobilePhone()).isTrue();
        assertThat(value.hashPurpose()).isEqualTo("contact.phone");
    }

    @Test
    void acceptsLandline() {
        ContactValue value = ContactValue.parse(ContactType.PHONE, "(21) 3456-7890");
        assertThat(value.value()).isEqualTo("+552134567890");
        assertThat(value.isMobilePhone()).isFalse();
        assertThat(value.masked()).isEqualTo("(21) *****-7890");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "(20) 98765-4321", "(11) 88765-4321", "(11) 1234-5678", "+1 415 555 0100",
            "119876543210"})
    void rejectsInvalidPhones(String raw) {
        assertThatThrownBy(() -> ContactValue.parse(ContactType.PHONE, raw)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").asString().startsWith("contact.phone_");
    }

    @Test
    void normalizesEmailToLowerCase() {
        ContactValue value = ContactValue.parse(ContactType.EMAIL, "  Maria.Silva@Exemplo.COM.br ");
        assertThat(value.value()).isEqualTo("maria.silva@exemplo.com.br");
        assertThat(value.masked()).isEqualTo("m***@exemplo.com.br");
        assertThat(value.hashKey()).isEqualTo(value.value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"maria", "maria@", "@exemplo.com", "maria@exemplo", "ma ria@exemplo.com"})
    void rejectsInvalidEmails(String raw) {
        assertThatThrownBy(() -> ContactValue.parse(ContactType.EMAIL, raw)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "contact.email_invalid");
    }

    @Test
    void addressHashIgnoresAccentsCaseAndPunctuation() {
        ContactValue a = ContactValue.parse(ContactType.ADDRESS, "Rua São João,  100 - Apto 12");
        ContactValue b = ContactValue.parse(ContactType.ADDRESS, "RUA SAO JOAO 100 APTO 12");
        assertThat(a.value()).isEqualTo("Rua São João, 100 - Apto 12");
        assertThat(a.hashKey()).isEqualTo(b.hashKey());
        assertThat(a.masked()).isEqualTo("Rua ***");
    }

    @Test
    void toStringNeverExposesValue() {
        assertThat(ContactValue.parse(ContactType.PHONE, "11987654321").toString()).doesNotContain("98765");
    }

    @Test
    void whatsappOnlyForPhones() {
        ContactValue email = ContactValue.parse(ContactType.EMAIL, "a@b.com");
        assertThatThrownBy(() -> new DebtorContact(java.util.UUID.randomUUID(), email, "h", ContactLabel.OTHER, true,
                ContactSource.MANUAL, 0)).hasFieldOrPropertyWithValue("code", "contact.whatsapp_requires_phone");
    }
}
