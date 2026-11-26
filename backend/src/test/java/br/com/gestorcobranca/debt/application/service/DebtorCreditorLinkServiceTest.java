package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.out.DebtorCreditorLinks;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RC2-02: credor fora do escopo ou id nulo não têm ligação, sem consultar o banco. */
class DebtorCreditorLinkServiceTest {

    private final DebtorCreditorLinks links = mock(DebtorCreditorLinks.class);
    private final AccessScope scope = mock(AccessScope.class);
    private final DebtorCreditorLinkService service = new DebtorCreditorLinkService(links, scope);

    @Test
    void outOfScopeCreditorOrNullIdsAreNotLinked() {
        UUID creditor = UUID.randomUUID();
        UUID debtor = UUID.randomUUID();
        when(scope.canAccessCreditor(creditor)).thenReturn(false);
        assertThat(service.linked(creditor, debtor)).isFalse();
        assertThat(service.linked(null, debtor)).isFalse();
        assertThat(service.linked(creditor, null)).isFalse();
        verifyNoInteractions(links);
    }

    @Test
    void inScopeCreditorAsksTheScalarQuery() {
        UUID creditor = UUID.randomUUID();
        UUID debtor = UUID.randomUUID();
        when(scope.canAccessCreditor(creditor)).thenReturn(true);
        when(links.linked(creditor, debtor)).thenReturn(true);
        assertThat(service.linked(creditor, debtor)).isTrue();
    }
}
