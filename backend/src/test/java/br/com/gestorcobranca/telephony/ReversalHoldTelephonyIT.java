package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement.PlaceCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-110 na telefonia: com estorno de pagamento recebido pelo credor em conferência, o clique para ligar é negado pelo
 * porteiro na verificação prévia e o discador não chega a discar para o devedor.
 */
class ReversalHoldTelephonyIT extends TelephonyTestSupport {

    @Autowired
    ReversalHoldPlacement placement;
    @Autowired
    TransactionTemplate transactions;

    private void hold(UUID debtorId) {
        system(() -> transactions.execute(s -> placement.place(new PlaceCommand(Ids.newId(), Ids.newId(), creditorA,
                debtorId, null, List.of(), List.of(), List.of(), null, "OTHER"))));
    }

    @Test
    void clickToCallIsDeniedWhileHeld() {
        provision("tel-retencao", false);
        UUID debtor = debtor("529.982.247-25");
        CaseView kase = loadCase(creditorA, portfolioA, debtor, "800.00");
        hold(debtor);

        asOperator(operatorX, () -> agents.login(null));
        CallView blocked = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        assertThat(blocked.status()).isEqualTo("FAILED");
        assertThat(blocked.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(blocked.denialReasons()).contains("PAYMENT_REVERSAL_HOLD");
    }

    @Test
    void dialerDoesNotDialWhileHeld() {
        provision("dlr-retencao", false);
        UUID debtor = debtor("529.982.247-25");
        loadCase(creditorA, portfolioA, debtor, "800.00");
        hold(debtor);
        UUID queue = queueA("DISCADOR");
        CampaignView campaign = asManager(() -> dialer.create(new CreateCampaignCommand("RET", null, queue,
                new CampaignSettings("Campanha RET", null, false, "PREDICTIVE", new BigDecimal("3"), null, 3, 30, 20,
                        null, null))));
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();
        assertThat(count("select count(*) from telephony.phone_call where campaign_id = ? and status = 'DIALING'",
                campaign.id())).isZero();
        assertThat(count("select count(*) from compliance.contact_attempt where debtor_id = ?", debtor)).isZero();
    }
}
