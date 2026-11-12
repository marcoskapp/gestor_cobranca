package br.com.gestorcobranca.portal.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeclaredNameTest {

    @Test
    void firstAndLastNameMatchIgnoringAccentsAndCase() {
        assertThat(DeclaredName.matches("João Sócio Fictício", "joao ficticio")).isTrue();
        assertThat(DeclaredName.matches("João Sócio Fictício", "  JOÃO   Sócio  FICTÍCIO ")).isTrue();
        assertThat(DeclaredName.matches("Maria Aparecida da Silva", "Maria Silva")).isTrue();
        assertThat(DeclaredName.matches("Ana Paula Souza-Lima", "Ana Lima")).isTrue();
    }

    @Test
    void differentFirstOrLastNameDoesNotMatch() {
        assertThat(DeclaredName.matches("João Sócio Fictício", "João Sócio")).isFalse();
        assertThat(DeclaredName.matches("João Sócio Fictício", "José Fictício")).isFalse();
        assertThat(DeclaredName.matches("João Sócio Fictício", "João")).isFalse();
        assertThat(DeclaredName.matches("João Sócio Fictício", "Fictício João")).isFalse();
    }

    @Test
    void singleRegisteredNameNeedsTheSameSingleWord() {
        assertThat(DeclaredName.matches("Xuxa", "xuxa")).isTrue();
        assertThat(DeclaredName.matches("Xuxa", "Xuxa Meneghel")).isFalse();
    }

    @Test
    void blankOrMissingNameNeverMatches() {
        assertThat(DeclaredName.matches(null, "João")).isFalse();
        assertThat(DeclaredName.matches("João", null)).isFalse();
        assertThat(DeclaredName.matches("João", "  ")).isFalse();
        assertThat(DeclaredName.matches(" - ", " - ")).isFalse();
    }
}
