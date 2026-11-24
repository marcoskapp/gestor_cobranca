package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.legal.domain.repository.LawFirmRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CreditorActorFixture;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** A pessoa do credor (ADR 0011) nunca recebe o escopo interno do jurídico. */
class CreditorActorLegalAccessTest {

    @BeforeEach
    void signIn() {
        CreditorActorFixture.signInCreditor();
    }

    @AfterEach
    void signOut() {
        CreditorActorFixture.signOut();
    }

    @Test
    void credorNaoRecebeEscopoInternoDoJuridico() {
        CurrentActor currentActor = new CurrentActor();
        AccessLogger logger = mock(AccessLogger.class);
        LegalAccess access = new LegalAccess(mock(LegalMatterRepository.class), mock(LawFirmMemberRepository.class),
                mock(LawFirmRepository.class), new AccessScope(currentActor, logger), currentActor, logger,
                mock(LegalMetrics.class));
        assertThatThrownBy(access::scope).isInstanceOf(ScopeAccessDeniedException.class);
    }
}
