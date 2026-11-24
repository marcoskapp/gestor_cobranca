package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.out.DebtorCaseLock;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CreditorActorFixture;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A pessoa do credor (ADR 0011) nunca é tratada como processo dispensado das regras da assessoria: não trabalha caso
 * nem contestação, não gerencia contestações — e a recusa fica no log de acesso.
 */
class CreditorActorCollectionAccessTest {

    private final AccessLogger accessLogger = mock(AccessLogger.class);
    private final CurrentActor currentActor = new CurrentActor();
    private final AccessScope accessScope = new AccessScope(currentActor, accessLogger);

    @BeforeEach
    void signIn() {
        CreditorActorFixture.signInCreditor();
    }

    @AfterEach
    void signOut() {
        CreditorActorFixture.signOut();
    }

    @Test
    void credorNaoTrabalhaOCasoERecusaFicaNoLog() {
        CaseAccess access = new CaseAccess(mock(CollectionCaseRepository.class), accessScope, currentActor,
                mock(DebtorCaseLock.class));
        assertThatThrownBy(() -> access.requireWorkable(mock(CollectionCase.class)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        ArgumentCaptor<AccessEvent> event = ArgumentCaptor.forClass(AccessEvent.class);
        verify(accessLogger).log(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AccessEventType.ACCESS_DENIED);
        assertThat(event.getValue().actorType()).isEqualTo("CREDITOR");
        assertThat(event.getValue().details()).containsEntry("actorType", "CREDITOR").containsEntry("resource", "collection_case");
    }

    @Test
    void credorNaoTrabalhaNemGerenciaContestacao() {
        DisputeAccess access = new DisputeAccess(mock(DisputeRepository.class), accessScope, currentActor);
        assertThat(access.canManage()).isFalse();
        assertThatThrownBy(access::requireManage).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> access.requireWork(mock(Dispute.class))).isInstanceOf(ScopeAccessDeniedException.class);
        verify(accessLogger, atLeastOnce()).log(org.mockito.ArgumentMatchers.any());
    }
}
