package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.campaign.CampaignTestSupport;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.DebtorInsolvency;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.ProceedingView;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.RegisterProceedingCommand;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recuperação judicial e falência do devedor PJ (cobrança B2B §7): o marcador suspende a cobrança em todos os credores
 * (casos inelegíveis com {@code DEBTOR_INSOLVENCY}, a régua não envia), só vale para empresa, exige escopo sobre todos
 * os credores do devedor, é auditado e, encerrado, devolve a elegibilidade (a régua volta a enviar).
 */
class InsolvencyIT extends CampaignTestSupport {

    /** Número CNJ válido (módulo 97) de 2026 — fictício. */
    private static final String CNJ = "0001234-03.2026.8.26.0100";
    private static final LocalDate FILED_ON = LocalDate.of(2026, 3, 2);

    @Autowired
    InsolvencyUseCases insolvency;

    @Autowired
    CaseEligibilityPort negotiationEligibility;

    UUID company;
    CaseView caseA;
    CaseView caseB;

    @BeforeEach
    void setUp() {
        provisionCampaigns("insolv");
        company = system(() -> ingestion.upsertDebtor(new DebtorRecord("12.345.678/0001-95", "Empresa Fictícia Ltda",
                null, List.of(new ContactRecord("PHONE", "(11) 91234-5678", "MOBILE", true)))).id());
        caseA = loadCase(creditorA, portfolioA, company, "500.00", LocalDate.of(2026, 3, 10));
        caseB = loadCase(creditorB, portfolioB, company, "300.00", LocalDate.of(2026, 3, 10));
        assertThat(caseA.eligible()).isTrue();
    }

    private RegisterProceedingCommand judicialRecovery() {
        return new RegisterProceedingCommand(company, "JUDICIAL_RECOVERY", CNJ, "1ª Vara de Falências e Recuperações",
                FILED_ON, "Edital publicado no diário oficial");
    }

    private CaseView currentCase(UUID caseId) {
        return asManager(() -> cases.get(caseId));
    }

    /** Mensagens de saída enviadas à empresa (o caso do credor A é o único com régua). */
    private long messagesOf(UUID caseId) {
        return count("select count(*) from communication.message m join communication.conversation c "
                + "on c.id = m.conversation_id where c.debtor_id = ? and m.direction = 'OUTBOUND'", company);
    }

    @Test
    void insolvencySuspendsCollectionInEveryCreditorAndEndingRestoresIt() {
        // régua em andamento: a primeira etapa sai antes do processo
        UUID segmentId = segment(creditorA, "TODOS_RJ", SegmentCriteria.any());
        CampaignView campaign = activeCampaign("REGUA_RJ", creditorA, settings(segmentId, null, 100),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D1", 1440, null, null)));
        runEngine();
        assertThat(messagesOf(caseA.id())).isEqualTo(1);

        ProceedingView registered = asSupervisor(() -> insolvency.register(judicialRecovery()));
        assertThat(registered.status()).isEqualTo("ACTIVE");
        assertThat(registered.caseNumber()).isEqualTo(CNJ);
        awaitIdle("collection");
        awaitTrue("casos do devedor inelegíveis nos dois credores", () ->
                currentCase(caseA.id()).eligibilityReasons().contains("DEBTOR_INSOLVENCY")
                        && currentCase(caseB.id()).eligibilityReasons().contains("DEBTOR_INSOLVENCY"));
        assertThat(currentCase(caseA.id()).eligible()).isFalse();
        // auditoria com motivo; evento de negócio sem texto livre
        assertThat(count("select count(*) from shared.audit_event where action = 'insolvency.registered' and entity_id = ?",
                registered.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'debtor.insolvency_registered' "
                + "and debtor_id = ? and attributes::text not like '%Edital%'", company)).isEqualTo(1);

        // a régua respeita a elegibilidade: a etapa seguinte não envia nada à empresa
        awaitIdle("campaign.application");
        fastForward(campaign.id());
        runEngine();
        assertThat(messagesOf(caseA.id())).isEqualTo(1);
        assertThat(enrollmentOf(campaign.id(), caseA.id()).status()).isEqualTo("ENDED");

        // convolação em falência mantém a suspensão
        ProceedingView bankruptcy = asSupervisor(() -> insolvency.convertToBankruptcy(registered.id(),
                LocalDate.of(2026, 6, 1), "Plano rejeitado em assembleia"));
        assertThat(bankruptcy.kind()).isEqualTo("BANKRUPTCY");
        assertThat(bankruptcy.convertedFrom()).isEqualTo("JUDICIAL_RECOVERY");
        assertThat(bankruptcy.since()).isEqualTo(LocalDate.of(2026, 6, 1));
        awaitIdle("collection");
        assertThat(currentCase(caseA.id()).eligibilityReasons()).contains("DEBTOR_INSOLVENCY");

        // encerrado o processo, a elegibilidade é reavaliada e a régua volta a enviar
        ProceedingView ended = asSupervisor(() -> insolvency.end(registered.id(), LocalDate.of(2026, 9, 1),
                "Falência encerrada por sentença"));
        assertThat(ended.status()).isEqualTo("ENDED");
        awaitIdle("collection");
        awaitTrue("casos elegíveis de novo", () -> currentCase(caseA.id()).eligible() && currentCase(caseB.id()).eligible());
        awaitIdle("campaign.application");
        activeCampaign("REGUA_POS", creditorA, settings(segmentId, null, 90), cadence(null, whatsapp("D0", 0, null, null)));
        runEngine();
        assertThat(messagesOf(caseA.id())).isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action in "
                + "('insolvency.converted', 'insolvency.ended')", registered.id())).isEqualTo(2);
        DebtorInsolvency view = asOperator(operatorZ, () -> insolvency.ofDebtor(company));
        assertThat(view.company()).isTrue();
        assertThat(view.active()).isNull();
        assertThat(view.history()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("ENDED"));
    }

    @Test
    void onlyCompaniesWithFullScopeAndOneActiveProceeding() {
        UUID person = debtorWithPhone("529.982.247-25", 7);
        loadCase(creditorA, portfolioA, person, "100.00", LocalDate.of(2026, 3, 10));
        assertThatThrownBy(() -> asSupervisor(() -> insolvency.register(new RegisterProceedingCommand(person,
                "JUDICIAL_RECOVERY", CNJ, null, FILED_ON, "Teste"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("insolvency.debtor_not_company");
        // número CNJ com dígito verificador errado, ano diferente do pedido e data futura
        assertThatThrownBy(() -> asSupervisor(() -> insolvency.register(new RegisterProceedingCommand(company,
                "JUDICIAL_RECOVERY", "0001234-04.2026.8.26.0100", null, FILED_ON, "Teste"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("insolvency.cnj_invalid");
        assertThatThrownBy(() -> asSupervisor(() -> insolvency.register(new RegisterProceedingCommand(company,
                "JUDICIAL_RECOVERY", CNJ, null, LocalDate.of(2025, 12, 1), "Teste"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("insolvency.cnj_year_mismatch");
        // operador restrito ao credor B vê a empresa, mas não suspende a cobrança do credor A (403)
        assertThatThrownBy(() -> asOperator(operatorZ, () -> insolvency.register(judicialRecovery())))
                .isInstanceOf(ScopeAccessDeniedException.class);

        ProceedingView extrajudicial = asSupervisor(() -> insolvency.register(new RegisterProceedingCommand(company,
                "EXTRAJUDICIAL_RECOVERY", CNJ, null, FILED_ON, "Plano homologado")));
        assertThatThrownBy(() -> asSupervisor(() -> insolvency.register(judicialRecovery())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("insolvency.already_active");
        assertThatThrownBy(() -> asSupervisor(() -> insolvency.convertToBankruptcy(extrajudicial.id(),
                LocalDate.of(2026, 5, 1), "Teste")))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("insolvency.conversion_not_allowed");
        assertThatThrownBy(() -> asOperator(operatorZ, () -> insolvency.end(extrajudicial.id(), LocalDate.of(2026, 5, 1),
                "Teste"))).isInstanceOf(ScopeAccessDeniedException.class);
        // o banco garante a coerência entre a situação e a data de encerramento
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update debt.insolvency_proceeding set status = 'ENDED' "
                + "where id = ?", extrajudicial.id()))).isInstanceOf(RuntimeException.class);

        // operador restrito ao credor B consulta (o caso dele está suspenso) e o caso da pessoa física segue elegível
        assertThat(asOperator(operatorZ, () -> insolvency.ofDebtor(company)).active().kind())
                .isEqualTo("EXTRAJUDICIAL_RECOVERY");
        awaitIdle("collection");
        assertThat(currentCase(caseB.id()).eligibilityReasons()).contains("DEBTOR_INSOLVENCY");

        // anonimização do devedor neutraliza o texto livre; o processo (dado público) permanece
        publishAndAwait(new DebtorAnonymized(Ids.newId(), tenant, Instant.now(), company), "debt.insolvency-anonymized");
        assertThat(count("select count(*) from debt.insolvency_proceeding where id = ? and note is null",
                extrajudicial.id())).isEqualTo(1);
    }

    @Test
    void theWholeCompanyIsSuspendedAndNoNewAgreementIsAllowed() {
        // filial fictícia da mesma empresa (mesma raiz do CNPJ), com caso no credor A
        UUID branch = system(() -> ingestion.upsertDebtor(new DebtorRecord("12.345.678/0002-76",
                "Empresa Fictícia Ltda - Filial", null, List.of(new ContactRecord("PHONE", "(11) 93456-7890", "MOBILE",
                true)))).id());
        CaseView branchCase = loadCase(creditorA, portfolioA, branch, "200.00", LocalDate.of(2026, 3, 10));
        List<UUID> titles = system(() -> jdbcTemplate.queryForList(
                "select id from debt.debt_title where debtor_id = ? and creditor_id = ?", UUID.class, company, creditorA));
        assertThat(asSupervisor(() -> negotiationEligibility.check(creditorA, company, titles)).eligible())
                .containsExactlyInAnyOrderElementsOf(titles);

        // quem tem escopo parcial vê o aviso, mas não as ações (o servidor exigiria todos os credores)
        assertThat(asOperator(operatorZ, () -> insolvency.ofDebtor(company)).canManage()).isFalse();
        assertThat(asSupervisor(() -> insolvency.ofDebtor(company)).canManage()).isTrue();

        asSupervisor(() -> insolvency.register(judicialRecovery()));
        awaitTrue("matriz e filial suspensas", () ->
                currentCase(caseA.id()).eligibilityReasons().contains("DEBTOR_INSOLVENCY")
                        && currentCase(branchCase.id()).eligibilityReasons().contains("DEBTOR_INSOLVENCY"));
        // a filial mostra o processo da matriz
        assertThat(asSupervisor(() -> insolvency.ofDebtor(branch)).active()).isNotNull();
        // acordo novo fora do plano: a negociação recusa os títulos (o acordo em vigor não é tocado)
        assertThat(asSupervisor(() -> negotiationEligibility.check(creditorA, company, titles)).ineligible())
                .allSatisfy((title, reasons) -> assertThat(reasons).contains("case.not_eligible"));
        // quem atende só o credor B também vê o processo em curso (o aviso da ficha do caso)
        assertThat(asOperator(operatorZ, () -> insolvency.ofDebtor(company)).active().kind())
                .isEqualTo("JUDICIAL_RECOVERY");
    }
}
