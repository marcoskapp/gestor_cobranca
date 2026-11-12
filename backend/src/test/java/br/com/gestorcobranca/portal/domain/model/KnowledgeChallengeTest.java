package br.com.gestorcobranca.portal.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChallengeTest {

    @Test
    void companyNeverGetsTheBirthDateChallenge() {
        assertThat(KnowledgeChallenge.BIRTH_DATE.forPerson(true)).isEqualTo(KnowledgeChallenge.CONTRACT_SUFFIX);
        assertThat(KnowledgeChallenge.BIRTH_DATE.forPerson(false)).isEqualTo(KnowledgeChallenge.BIRTH_DATE);
        assertThat(KnowledgeChallenge.NONE.forPerson(true)).isEqualTo(KnowledgeChallenge.NONE);
        assertThat(KnowledgeChallenge.CONTRACT_SUFFIX.forPerson(true)).isEqualTo(KnowledgeChallenge.CONTRACT_SUFFIX);
    }
}
