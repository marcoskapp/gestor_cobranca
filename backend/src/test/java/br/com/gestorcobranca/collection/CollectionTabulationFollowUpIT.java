package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.FollowUpInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationCommand;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationView;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ação automática da tabulação (decisões 776–780) contra PostgreSQL real: "Alega pagamento" e
 * "Contesta a dívida" abrem contestação, "Informado falecimento" bloqueia o contato do devedor em
 * todos os credores, "Contato errado" invalida o contato usado — na mesma transação do registro,
 * com rastreio nos dois sentidos, idempotência e atomicidade (falha da ação desfaz o registro).
 */
class CollectionTabulationFollowUpIT extends CollectionTestSupport {

    @Autowired
    DebtorContactUseCases debtorContacts;

    @BeforeEach
    void setUp() {
        provision("fup");
    }

    @Test
    void defaultCatalogCarriesTheFollowUps() {
        Map<String, TabulationView> catalog = system(() -> tabulations.list(false)).stream()
                .collect(Collectors.toMap(TabulationView::code, t -> t));
        assertThat(catalog.get("ALREADY_PAID").followUp()).isEqualTo("OPEN_DISPUTE");
        assertThat(catalog.get("ALREADY_PAID").disputeType()).isEqualTo("ALREADY_PAID");
        assertThat(catalog.get("DISPUTE").followUp()).isEqualTo("OPEN_DISPUTE");
        assertThat(catalog.get("DISPUTE").disputeType()).isNull();
        assertThat(catalog.get("DECEASED").followUp()).isEqualTo("REPORT_DECEASED");
        assertThat(catalog.get("WRONG_CONTACT").followUp()).isEqualTo("INVALIDATE_CONTACT");
        assertThat(catalog.get("PROMISE").followUp()).isEqualTo("NONE");
    }

    @Test
    void paymentClaimOpensDisputeLinkedToTheInteraction() {
        UUID debtor = debtor("526.018.159-06");
        List<UUID> titles = load(creditorA, portfolioA, debtor, "120.00", "80.00");
        CaseView c = activeCase(debtor, portfolioA);

        RecordResult result = record(operatorX, c.id(), interaction("ALREADY_PAID", null, null,
                "Diz que pagou no caixa da loja em março"));

        assertThat(result.followUp().action()).isEqualTo("OPEN_DISPUTE");
        assertThat(result.followUp().outcome()).isEqualTo("DONE");
        assertThat(result.followUp().referenceType()).isEqualTo("dispute");
        assertThat(result.interaction().followUp()).isEqualTo("OPEN_DISPUTE");
        assertThat(result.collectionCase().status()).isEqualTo("DISPUTED");
        DisputeView dispute = system(() -> disputes.get(result.followUp().referenceId()));
        assertThat(dispute.type()).isEqualTo("ALREADY_PAID");
        assertThat(dispute.origin()).isEqualTo("OPERATOR");
        assertThat(dispute.description()).isEqualTo("Diz que pagou no caixa da loja em março");
        assertThat(dispute.blocksCollection()).isTrue();
        assertThat(dispute.titleIds()).containsExactlyInAnyOrderElementsOf(titles);
        assertThat(dispute.originInteractionId()).isEqualTo(result.interaction().id());
        assertThat(count("select count(*) from shared.business_event where event_type = 'interaction.follow_up' "
                + "and case_id = ? and attributes ->> 'outcome' = 'DONE'", c.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.opened' "
                + "and case_id = ? and attributes ->> 'trigger' = 'tabulation'", c.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'interaction.follow_up_executed' "
                + "and entity_id = ?", result.interaction().id())).isEqualTo(1);

        // nova alegação com a contestação em aberto: registra a interação e aponta a contestação existente
        RecordResult again = record(operatorX, c.id(), interaction("ALREADY_PAID", null, null, "Reforça que pagou"));
        assertThat(again.followUp().outcome()).isEqualTo("ALREADY_IN_EFFECT");
        assertThat(again.followUp().referenceId()).isEqualTo(dispute.id());
        assertThat(count("select count(*) from collection.dispute where case_id = ?", c.id())).isEqualTo(1);
    }

    @Test
    void disputeWithoutDefaultTypeRequiresTheTypeAndFailureRollsBackTheInteraction() {
        UUID debtor = debtor("628.194.821-12");
        load(creditorA, portfolioA, debtor, "300.00");
        CaseView c = activeCase(debtor, portfolioA);

        assertThatThrownBy(() -> record(operatorX, c.id(), interaction("DISPUTE", null, null, "Não reconhece")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("informe o tipo");
        // título que não é do caso: a contestação falha e a interação não fica registrada
        assertThatThrownBy(() -> record(operatorX, c.id(), withFollowUp("DISPUTE", "Não reconhece",
                new FollowUpInput("FRAUD", Set.of(Ids.newId())))))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(count("select count(*) from collection.interaction where case_id = ?", c.id())).isZero();
        assertThat(count("select count(*) from collection.dispute where case_id = ?", c.id())).isZero();

        RecordResult result = record(operatorX, c.id(), withFollowUp("DISPUTE", "Contratação por terceiro",
                new FollowUpInput("FRAUD", null)));
        assertThat(system(() -> disputes.get(result.followUp().referenceId())).type()).isEqualTo("FRAUD");
        assertThat(count("select count(*) from collection.interaction where case_id = ?", c.id())).isEqualTo(1);
    }

    @Test
    void deceasedReportBlocksContactAcrossCreditorsAtOnce() {
        UUID debtor = debtor("111.444.777-35");
        load(creditorA, portfolioA, debtor, "500.00");
        load(creditorB, portfolioB, debtor, "90.00");
        CaseView c = activeCase(debtor, portfolioA);
        CaseView other = activeCase(debtor, portfolioB);
        assertThat(other.eligible()).isTrue();

        RecordResult result = record(operatorX, c.id(), interaction("DECEASED", null, null,
                "Filha atendeu e informou o falecimento"));

        assertThat(result.followUp().outcome()).isEqualTo("DONE");
        assertThat(result.followUp().referenceType()).isEqualTo("contact_restriction");
        assertThat(result.collectionCase().eligible()).isFalse();
        RestrictionView restriction = asSupervisor(() -> restrictions.get(result.followUp().referenceId()));
        assertThat(restriction.type()).isEqualTo("DECEASED");
        assertThat(restriction.creditorId()).isNull();
        assertThat(restriction.channel()).isNull();
        assertThat(restriction.source()).isEqualTo("OPERATOR");
        assertThat(restriction.effective()).isTrue();
        assertThat(restriction.originInteractionId()).isEqualTo(result.interaction().id());
        // a observação (dado de terceiro) não é copiada para o motivo da restrição
        assertThat(restriction.reason()).doesNotContain("Filha");

        awaitIdle("CollectionEventListeners");
        assertThat(system(() -> cases.get(other.id())).eligible()).isFalse();

        RecordResult again = record(operatorX, c.id(), interaction("DECEASED", null, null, "Confirmado pelo cônjuge"));
        assertThat(again.followUp().outcome()).isEqualTo("ALREADY_IN_EFFECT");
        assertThat(again.followUp().referenceId()).isEqualTo(restriction.id());
        assertThat(count("select count(*) from compliance.contact_restriction where debtor_id = ? and type = 'DECEASED'",
                debtor)).isEqualTo(1);
    }

    @Test
    void wrongContactInvalidatesTheContactUsed() {
        UUID debtor = debtor("390.533.447-05");
        load(creditorA, portfolioA, debtor, "75.00");
        CaseView c = activeCase(debtor, portfolioA);

        assertThatThrownBy(() -> record(operatorX, c.id(), interaction("WRONG_CONTACT", null, null, "Não conhece")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("informe o contato");

        ContactView phone = system(() -> debtorContacts.list(debtor)).stream().filter(v -> v.type().equals("PHONE"))
                .findFirst().orElseThrow();
        RecordResult result = record(operatorX, c.id(), withContact("WRONG_CONTACT", "Número é de outra pessoa",
                phone.id()));
        assertThat(result.followUp().outcome()).isEqualTo("DONE");
        assertThat(result.followUp().referenceId()).isEqualTo(phone.id());
        ContactView after = system(() -> debtorContacts.list(debtor)).stream().filter(v -> v.id().equals(phone.id()))
                .findFirst().orElseThrow();
        assertThat(after.status()).isEqualTo("INVALID");
        assertThat(after.invalidReason()).doesNotContain("outra pessoa");

        RecordResult again = record(operatorX, c.id(), withContact("WRONG_CONTACT", "De novo", phone.id()));
        assertThat(again.followUp().outcome()).isEqualTo("ALREADY_IN_EFFECT");
    }

    @Test
    void followUpDetailsOnlyWithTabulationsThatHaveTheAction() {
        UUID debtor = debtor("529.982.247-25");
        load(creditorA, portfolioA, debtor, "60.00");
        CaseView c = activeCase(debtor, portfolioA);

        assertThatThrownBy(() -> record(operatorX, c.id(), withFollowUp("CPC_NEGOTIATING", "Conversou",
                new FollowUpInput("FRAUD", null))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não tem ação automática");
        assertThatThrownBy(() -> record(operatorX, c.id(), withFollowUp("DECEASED", "Falecido",
                new FollowUpInput("FRAUD", null))))
                .isInstanceOf(BusinessRuleException.class);
        RecordResult plain = record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        assertThat(plain.followUp()).isNull();
        assertThat(plain.interaction().followUp()).isEqualTo("NONE");
    }

    @Test
    void catalogEnforcesTheFollowUpRules() {
        assertThatThrownBy(() -> asManager(() -> tabulations.create(new TabulationCommand("OBITO_SEM_NOTA", "Óbito",
                false, false, false, false, "REPORT_DECEASED", null, 200))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> asManager(() -> tabulations.create(new TabulationCommand("CONTESTA_SEM_CPC", "Contesta",
                false, false, false, true, "OPEN_DISPUTE", "FRAUD", 200))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> asManager(() -> tabulations.create(new TabulationCommand("TIPO_SEM_ACAO", "Tipo",
                true, false, false, true, null, "FRAUD", 200))))
                .isInstanceOf(BusinessRuleException.class);
        TabulationView fraud = asManager(() -> tabulations.create(new TabulationCommand("FRAUDE", "Alega fraude",
                true, false, false, true, "OPEN_DISPUTE", "FRAUD", 200)));
        assertThat(fraud.followUp()).isEqualTo("OPEN_DISPUTE");
        assertThat(fraud.disputeType()).isEqualTo("FRAUD");
        assertThat(count("select count(*) from shared.audit_event where action = 'tabulation.created' and entity_id = ? "
                + "and after_state ->> 'flags' like '%followUp=OPEN_DISPUTE%'", fraud.id())).isEqualTo(1);
    }

    private static RecordInteractionCommand withFollowUp(String tabulation, String note, FollowUpInput followUp) {
        return new RecordInteractionCommand("PHONE", "OUTBOUND", null, tabulation, note, null, null, null, null, null,
                null, null, null, followUp);
    }

    private static RecordInteractionCommand withContact(String tabulation, String note, UUID contactId) {
        return new RecordInteractionCommand("PHONE", "OUTBOUND", null, tabulation, note, contactId, null, null, null,
                null, null, null, null, null);
    }
}
