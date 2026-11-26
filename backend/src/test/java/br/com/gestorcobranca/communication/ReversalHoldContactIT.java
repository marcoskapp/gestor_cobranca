package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement.PlaceCommand;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-110 no envio: mensagem pedida antes do estorno (na fila) é barrada pelo porteiro no {@code SendMessageJob} quando
 * a retenção aparece; o pedido feito com a retenção falha na verificação prévia; depois da liberação, o envio volta.
 */
class ReversalHoldContactIT extends CommunicationTestSupport {

    @Autowired
    ReversalHoldPlacement placement;
    @Autowired
    ReversalHoldUseCases holds;
    @Autowired
    TransactionTemplate transactions;

    UUID debtor;

    @BeforeEach
    void setUp() {
        provision("com-retencao");
        debtor = debtor("529.982.247-25");
        loadCase(creditorA, portfolioA, debtor, "500.00");
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
    }

    private SendMessageCommand template() {
        return new SendMessageCommand(null, debtor, creditorA, null, "WHATSAPP", null, "COBRANCA",
                Map.of("nome", "Maria", "valor", "500.00"), null, null, null);
    }

    private UUID placeHold() {
        return system(() -> transactions.execute(s -> placement.place(new PlaceCommand(Ids.newId(), Ids.newId(), creditorA,
                debtor, null, List.of(), List.of(), List.of(), null, "OTHER")).orElseThrow()));
    }

    @Test
    void queuedMessageIsDeniedAtSendTimeAndContactResumesAfterRelease() {
        MessageView queued = asOperator(operatorX, () -> messages.send(template()));
        assertThat(queued.status()).isEqualTo("QUEUED");
        UUID hold = placeHold();
        drainCommunication();
        MessageView denied = system(() -> messages.get(queued.id()));
        assertThat(denied.status()).isEqualTo("FAILED");
        assertThat(denied.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(denied.failureDetail()).contains("PAYMENT_REVERSAL_HOLD");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", queued.id())).isZero();

        // pedido novo com a retenção: recusado na verificação prévia, sem job
        MessageView blocked = asOperator(operatorX, () -> messages.send(template()));
        assertThat(blocked.status()).isEqualTo("FAILED");
        assertThat(blocked.failureDetail()).contains("PAYMENT_REVERSAL_HOLD");

        // estorno pelo sistema (provedor): qualquer pessoa com PAYMENT_REVIEW libera
        long version = system(() -> jdbcTemplate.queryForObject("select version from debt.reversal_hold where id = ?",
                Long.class, hold));
        asAdmin(() -> holds.release(hold, new ReleaseCommand(version, "CREDITOR_CONFIRMED_NOT_RECEIVED",
                "Credor confirmou que não recebeu", true)));
        MessageView resumed = asOperator(operatorX, () -> messages.send(template()));
        assertThat(resumed.status()).isEqualTo("QUEUED");
    }
}
