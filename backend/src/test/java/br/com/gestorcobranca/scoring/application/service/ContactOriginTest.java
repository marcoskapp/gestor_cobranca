package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactReceiptView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contato novo × antigo na inteligência de contato: novo é o que chegou em carga depois do cadastro do devedor. */
class ContactOriginTest {

    private static final Instant T = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void contactWithNoveltyWindowIsNewEvenAfterTheWindowEnds() {
        assertThat(ContactFactRecorder.origin(contact(new ContactReceiptView("NEW", T, T.plusSeconds(86_400), null, null))))
                .isEqualTo("NEW");
        // janela encerrada (sem selo): continua sendo um contato recebido depois do cadastro
        assertThat(ContactFactRecorder.origin(contact(new ContactReceiptView(null, T, T.plusSeconds(86_400), null, T))))
                .isEqualTo("NEW");
    }

    @Test
    void contactsFromTheFirstLoadOrManualAreExisting() {
        assertThat(ContactFactRecorder.origin(contact(new ContactReceiptView(null, T, null, null, null))))
                .isEqualTo("EXISTING");
        assertThat(ContactFactRecorder.origin(contact(null))).isEqualTo("EXISTING");
    }

    private static ContactView contact(ContactReceiptView receipt) {
        return new ContactView(UUID.randomUUID(), UUID.randomUUID(), "PHONE", "(11) *****-4321", "MOBILE", true,
                "IMPORT", "UNVERIFIED", 0, null, null, null, null, false, receipt);
    }
}
