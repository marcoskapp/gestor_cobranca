package br.com.gestorcobranca.communication.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAddressesTest {

    @Test
    void convertsWhatsAppIdsToE164RestoringTheNinthDigit() {
        assertThat(ProviderAddresses.fromWhatsApp("5511987654321")).contains("+5511987654321");
        assertThat(ProviderAddresses.fromWhatsApp("551187654321")).contains("+5511987654321");
        assertThat(ProviderAddresses.fromWhatsApp("551132654321")).contains("+551132654321");
        assertThat(ProviderAddresses.fromWhatsApp("14155550100")).isEmpty();
        assertThat(ProviderAddresses.fromWhatsApp("55")).isEmpty();
        assertThat(ProviderAddresses.toWhatsApp("+5511987654321")).isEqualTo("5511987654321");
    }

    @Test
    void normalizesEmail() {
        assertThat(ProviderAddresses.email(" Maria@Exemplo.COM ")).contains("maria@exemplo.com");
        assertThat(ProviderAddresses.email("sem-arroba")).isEmpty();
    }
}
