package br.com.gestorcobranca.assistant.domain.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityChallengeTest {

    @Test
    void parsesPrefixAndBirthDate() {
        assertThat(IdentityChallenge.parse("529 e 15/03/1985")).get()
                .isEqualTo(new IdentityChallenge.Answer("529", LocalDate.of(1985, 3, 15)));
        assertThat(IdentityChallenge.parse("nasci 15-03-1985, cpf começa com 529982")).get()
                .extracting(IdentityChallenge.Answer::documentPrefix).isEqualTo("529982");
    }

    @Test
    void fullCpfUsesOnlyFirstDigits() {
        assertThat(IdentityChallenge.parse("529.982.247-25 15/03/1985")).get()
                .extracting(IdentityChallenge.Answer::documentPrefix).isEqualTo("529");
    }

    @Test
    void incompleteOrInvalidIsEmpty() {
        assertThat(IdentityChallenge.parse("oi, quero negociar")).isEmpty();
        assertThat(IdentityChallenge.parse("529")).isEmpty();
        assertThat(IdentityChallenge.parse("529 31/02/1985")).isEmpty();
    }
}
