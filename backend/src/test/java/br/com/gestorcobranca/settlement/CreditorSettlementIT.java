package br.com.gestorcobranca.settlement;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.AcknowledgeCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.ContestCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.FileItem;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases.StatementDetail;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases.ExportView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.SendCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prestação de contas no portal do credor (ADR 0011, fatia 4): só demonstrativos enviados e só do credor da sessão
 * (os demais respondem 404), aceite com a nova verificação do MFA e os hashes dos arquivos vistos (mudou → 409),
 * contestação com motivo, termo só de inclusão, origem da resposta e registro do download. A nova verificação em si
 * tem o IT da identidade ({@code CreditorPortalIdentityIT}); aqui ela é simulada.
 */
class CreditorSettlementIT extends SettlementTestSupport {

    @MockitoBean
    CreditorStepUp stepUp;
    /** A conferência real do comprovante tem o IT da identidade ({@code CreditorPortalIdentityIT}); aqui é simulada. */
    @MockitoBean
    br.com.gestorcobranca.shared.security.StepUpProofs stepUpProofs;
    @Autowired
    CreditorStatementUseCases portal;
    @Autowired
    CreditorSettlementUseCases creditorSettlement;
    @Autowired
    CreditorHomeUseCases home;
    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    private final UUID creditorUser = Ids.newId();
    private final UUID session = Ids.newId();

    @Test
    void creditorSeesOnlyItsSentStatementsAcknowledgesWithMfaAndContests() throws Exception {
        provision("credst");
        reset(stepUp, stepUpProofs);
        Instant verifiedAt = Instant.now();
        when(stepUp.verify(anyString(), eq(br.com.gestorcobranca.shared.security.StepUpPurpose.STATEMENT_ACKNOWLEDGE),
                anyString())).thenReturn("comprovante");
        when(stepUpProofs.consume(eq("comprovante"), eq(br.com.gestorcobranca.shared.security.StepUpPurpose.STATEMENT_ACKNOWLEDGE),
                anyString())).thenReturn(verifiedAt);

        // período em aberto: o credor vê a própria posição sem permissão interna (primeiro cálculo é dele, sem cache)
        assertThat(asCreditor(creditorA, Permission.CREDITOR_ONLY, portal::openPeriod).direction())
                .isIn("AGENCY_TO_CREDITOR", "CREDITOR_TO_AGENCY", "NONE");
        assertThatThrownBy(() -> asFinance1(creditorSettlement::openPeriod)).isInstanceOf(ScopeAccessDeniedException.class);

        StatementView first = approved(today.minusDays(20), today.minusDays(11));
        ExportView csv = asFinance1(() -> exports.export(first.id(), "CSV"));
        ExportView pdf = asFinance1(() -> exports.export(first.id(), "PDF"));

        // aprovado e ainda não enviado: não existe para o credor
        assertThat(asCreditor(creditorA, Permission.CREDITOR_ONLY, portal::statements)).isEmpty();
        assertThatThrownBy(() -> asCreditor(creditorA, Permission.CREDITOR_ONLY, () -> portal.statement(first.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        asFinance1(() -> statements.send(first.id(), new SendCommand("PORTAL", null, List.of(csv.id(), pdf.id()))));

        StatementDetail detail = asCreditor(creditorA, Permission.CREDITOR_ONLY, () -> portal.statement(first.id()));
        assertThat(detail.statement().status()).isEqualTo("SENT");
        // prazo para responder (fatia 7): envio + 10 dias corridos, na lista, na ficha e na ficha interna
        java.time.LocalDate dueOn = java.time.LocalDate.ofInstant(detail.statement().sentAt(),
                br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE).plusDays(10);
        assertThat(detail.statement().responseDueOn()).isEqualTo(dueOn);
        assertThat(detail.statement().responseOverdue()).isFalse();
        assertThat(detail.statement().outstanding()).isEqualByComparingTo(detail.outstanding());
        assertThat(asFinance1(() -> statements.get(first.id())).responseDueOn()).isEqualTo(dueOn);
        assertThat(asCreditor(creditorA, Permission.CREDITOR_ONLY, portal::statements)).singleElement()
                .satisfies(i -> assertThat(i.responseDueOn()).isEqualTo(dueOn));
        // "Aguardando você": o demonstrativo enviado, pelo prazo; quem não responde vê sem poder agir
        CreditorHomeUseCases.Awaiting awaiting = asCreditor(creditorA, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                home::awaiting);
        assertThat(awaiting.counts().statements()).isEqualTo(1);
        assertThat(awaiting.items()).filteredOn(i -> i.kind().equals("STATEMENT")).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(first.id());
            assertThat(i.dueOn()).isEqualTo(dueOn);
            assertThat(i.youCanAct()).isFalse();
            assertThat(i.statement().sequenceNumber()).isEqualTo(first.sequenceNumber());
            assertThat(i.statement().periodStart()).isEqualTo(first.periodStart());
        });
        assertThat(asCreditor(creditorB, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW), home::awaiting).counts().statements())
                .isZero();
        // contrato das respostas da prestação de contas (RS-26, SEG-F05)
        br.com.gestorcobranca.support.JsonContract.assertMatches(json.valueToTree(detail),
                "creditor-portal/statement-detail.txt");
        br.com.gestorcobranca.support.JsonContract.assertMatches(json.valueToTree(
                asCreditor(creditorA, Permission.CREDITOR_ONLY, portal::openPeriod)), "creditor-portal/open-period.txt");
        assertThat(detail.files()).extracting(FileItem::sha256).containsExactlyInAnyOrder(csv.sha256(), pdf.sha256());
        List<String> hashes = detail.files().stream().map(FileItem::sha256).toList();

        // outro credor e o usuário da assessoria não chegam ao demonstrativo pelo portal
        assertThat(asCreditor(creditorB, Permission.CREDITOR_ONLY, portal::statements)).isEmpty();
        long denied = count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and tenant_id = ?",
                tenant);
        assertThatThrownBy(() -> asCreditor(creditorB, Permission.CREDITOR_ONLY, () -> portal.statement(first.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asCreditor(creditorB, Permission.CREDITOR_ONLY, () -> portal.contest(first.id(),
                new ContestCommand("tentativa", "203.0.113.9")))).isInstanceOf(ResourceNotFoundException.class);
        // outro credor fica registrado no log de acesso (RS-06), com o mesmo 404
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and tenant_id = ?",
                tenant)).isEqualTo(denied + 2);
        assertThatThrownBy(() -> asFinance1(creditorSettlement::statements))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // download registrado no log de acesso
        long downloads = count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' "
                + "and tenant_id = ?", tenant);
        try (var in = asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.download(first.id(), detail.files().getFirst().id())).content()) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' and tenant_id = ?",
                tenant)).isEqualTo(downloads + 1);

        // perfil só de leitura não responde nem gasta o código; arquivos diferentes dos vistos → 409; sem código → recusado
        assertThatThrownBy(() -> asCreditor(creditorA, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> portal.acknowledge(first.id(), new AcknowledgeCommand("123456", null, hashes, "203.0.113.7"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        verify(stepUp, never()).verify(anyString(), any(), anyString());
        assertThatThrownBy(() -> asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.acknowledge(first.id(), new AcknowledgeCommand("123456", null, List.of(csv.sha256()),
                        "203.0.113.7"))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "statement.files_changed");
        assertThatThrownBy(() -> asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.acknowledge(first.id(), new AcknowledgeCommand(null, null, hashes, "203.0.113.7"))))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(count("select count(*) from settlement.statement_creditor_term where tenant_id = ?", tenant)).isZero();

        StatementDetail acknowledged = asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.acknowledge(first.id(), new AcknowledgeCommand("123456", "Conferido", hashes,
                        "203.0.113.7")));
        assertThat(acknowledged.statement().status()).isEqualTo("ACKNOWLEDGED");
        assertThat(asCreditor(creditorA, Permission.CREDITOR_ONLY, home::awaiting).counts().statements()).isZero();
        assertThat(acknowledged.respondedInPortal()).isTrue();
        verify(stepUp, org.mockito.Mockito.atLeastOnce()).verify("123456",
                br.com.gestorcobranca.shared.security.StepUpPurpose.STATEMENT_ACKNOWLEDGE, first.id().toString());
        verify(stepUpProofs, org.mockito.Mockito.atLeastOnce()).consume("comprovante",
                br.com.gestorcobranca.shared.security.StepUpPurpose.STATEMENT_ACKNOWLEDGE, first.id().toString());
        assertThat(count("select count(*) from settlement.statement where id = ? and creditor_response_origin = "
                + "'CREDITOR_PORTAL' and creditor_response_by = ?", first.id(), creditorUser)).isEqualTo(1);
        assertThat(count("select count(*) from settlement.statement_creditor_term where statement_id = ? and decision = "
                        + "'ACKNOWLEDGED' and creditor_user_id = ? and session_id = ? and mfa_verified_at is not null "
                        + "and client_ip = '203.0.113.7' and jsonb_array_length(files) = 2 and length(term_sha256) = 64 "
                        + "and note = 'Conferido'",
                first.id(), creditorUser, session)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'settlement_statement.acknowledged' "
                + "and entity_id = ? and actor_type = 'CREDITOR' and actor_id = ?", first.id(), creditorUser)).isEqualTo(1);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update settlement.statement_creditor_term set decision = 'CONTESTED'")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from settlement.statement_creditor_term")))
                .isInstanceOf(DataAccessException.class);

        // contestação: motivo obrigatório, sem código do autenticador
        StatementView second = approved(today.minusDays(10), today.minusDays(1));
        ExportView csv2 = asFinance1(() -> exports.export(second.id(), "CSV"));
        asFinance1(() -> statements.send(second.id(), new SendCommand("PORTAL", null, List.of(csv2.id()))));
        // arquivo de outro demonstrativo pelo endereço deste: 404
        assertThatThrownBy(() -> asCreditor(creditorA, Permission.CREDITOR_ONLY, () -> portal.download(first.id(), csv2.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        reset(stepUp, stepUpProofs);
        assertThatThrownBy(() -> asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.contest(second.id(), new ContestCommand(" ", "203.0.113.7"))))
                .isInstanceOf(BusinessRuleException.class);
        StatementDetail contested = asCreditor(creditorA, Permission.CREDITOR_ONLY,
                () -> portal.contest(second.id(), new ContestCommand("Faltou o pagamento do dia 3", "203.0.113.7")));
        assertThat(contested.statement().status()).isEqualTo("CONTESTED");
        assertThat(contested.responseNote()).isEqualTo("Faltou o pagamento do dia 3");
        verify(stepUp, never()).verify(anyString(), any(), anyString());
        assertThat(count("select count(*) from settlement.statement_creditor_term where statement_id = ? and decision = "
                + "'CONTESTED' and mfa_verified_at is null and note = 'Faltou o pagamento do dia 3'", second.id())).isEqualTo(1);
        // a assessoria não registra aceite por cima da contestação feita pelo credor no portal (decisão 1420)
        assertThatThrownBy(() -> asFinance1(() -> statements.acknowledge(second.id(), "Credor concordou por telefone")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "statement.creditor_portal_response");
        // a ficha interna não expõe o id da pessoa do credor, só a origem
        StatementView internal = asFinance1(() -> statements.get(second.id()));
        assertThat(internal.creditorResponseOrigin()).isEqualTo("CREDITOR_PORTAL");
        assertThat(internal.creditorResponseBy()).isNull();
        assertThat(asCreditor(creditorA, Permission.CREDITOR_ONLY, portal::statements))
                .extracting(CreditorStatementUseCases.StatementItem::id).containsExactly(second.id(), first.id());
    }

    /** Demonstrativo calculado, revisado e aprovado (segregação: financeiro calcula, gestor aprova). */
    private StatementView approved(LocalDate start, LocalDate end) {
        StatementView created = asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, start, end)));
        asFinance1(() -> statements.calculate(created.id()));
        asFinance1(() -> statements.review(created.id()));
        return asManager(() -> statements.approve(created.id()));
    }

    private <T> T asCreditor(UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        return tenantExecution.callAs(Actor.creditor(tenant, creditorUser, session, creditorId, permissions), action);
    }
}
