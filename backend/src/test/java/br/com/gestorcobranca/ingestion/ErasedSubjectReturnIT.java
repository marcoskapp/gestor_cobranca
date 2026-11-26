package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestFilter;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestView;
import br.com.gestorcobranca.compliance.application.port.in.ErasedSubjectReturnUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ErasedSubjectReturnUseCases.ReturnsView;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentReversal;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.RegisterDebtorCommand;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases.AddPartyCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Decisão 1471 (SEG-20261124-D01h): o titular eliminado a pedido não volta como devedor nem como coobrigado, por nenhum
 * caminho — carga (devedor ou coobrigado da linha), cadastro manual ou coobrigado manual. A recusa é neutra e só da
 * linha (decisão 1498: o lote não é bloqueado e o texto não cita eliminação), não vale na prévia, e cada tentativa fica
 * registrada no compliance para revisão. O índice é gravado também quando o pedido não tinha documento (decisão 1499).
 */
class ErasedSubjectReturnIT extends IngestionTestSupport {

    static final String LEGAL_HEADER = HEADER + ";coobrigado_1_documento;coobrigado_1_nome;coobrigado_1_papel";

    @Autowired
    DebtLedgerPort ledger;
    @Autowired
    DebtorUseCases debtors;
    @Autowired
    ObligationPartyUseCases parties;
    @Autowired
    DataSubjectRequestUseCases requests;
    @Autowired
    ErasedSubjectReturnUseCases returns;

    @Test
    void subjectErasedOnRequestDoesNotComeBackThroughAnyPath() {
        Erased lia = importPayAndErase(cpf(21), "Lia", "R-21", null);
        Map<String, Object> request = system(() -> jdbcTemplate.queryForMap("select erased_subject_hash, executed_by, "
                + "document_hash from compliance.data_subject_request where id = ?", lia.requestId()));
        // sem documento informado no pedido: o índice vem do devedor eliminado, e só o índice (nunca o documento)
        assertThat(request.get("document_hash")).isNull();
        assertThat(request.get("erased_subject_hash")).isNotNull();
        assertThat(request.get("executed_by")).isNotNull();

        // carga com um título novo dela, ela como coobrigada de outra pessoa, e outra pessoa sem nada a ver
        String csv = LEGAL_HEADER + "\n" + line(cpf(21), "Lia", "N-21", "40,00", null) + ";;;"
                + "\n" + line(cpf(22), "Rui", "R-22", "50,00", null) + ";" + cpf(21) + ";Lia;Avalista"
                + "\n" + line(cpf(23), "Sol", "R-23", "60,00", null) + ";;;" + "\n";
        long debtorsBefore = count("select count(*) from debt.debtor");
        ImportBatchView batch = uploadCsv("volta.csv", csv, "IMPORT");
        runJobs();
        assertThat(batch(batch.id()).status()).isEqualTo("COMPLETED_WITH_ERRORS");
        List<ImportIssueView> refused = issues(batch.id()).stream()
                .filter(i -> DebtIngestionPort.SUBJECT_NOT_ACCEPTED.equals(i.code())).toList();
        assertThat(refused).extracting(ImportIssueView::rowNumber, ImportIssueView::field, ImportIssueView::severity)
                .containsExactly(tuple(2L, null, "ERROR"), tuple(3L, null, "ERROR"));
        // neutra: não conta ao credor que a pessoa pediu eliminação nem que foi devedora de outro credor
        assertThat(refused).allSatisfy(i -> assertThat(i.message()).doesNotContainIgnoringCase("elimin")
                .doesNotContain("LGPD").doesNotContain("Lia").doesNotContain(cpf(21)));
        // só a linha é recusada: o lote não fica bloqueado nem pede novo arquivo
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and blocked_reason is null",
                batch.id())).isEqualTo(1);
        assertThat(count("select count(*) from ingestion.import_subject_block where batch_id = ?", batch.id())).isZero();
        assertThat(count("select count(*) from debt.debtor")).isEqualTo(debtorsBefore + 1);
        assertThat(count("select count(*) from debt.debtor where status = 'ACTIVE' and document_hash is not null "
                + "and id in (select debtor_id from ingestion.import_batch_row where batch_id = ?)", batch.id()))
                .isEqualTo(1);
        // a tentativa fica para revisão: o pedido, o credor, o lote e as duas linhas
        assertThat(system(() -> jdbcTemplate.queryForList("select request_id, creditor_id, source, batch_id, lines, "
                + "outcome from compliance.erased_subject_return where batch_id = ?", batch.id())))
                .singleElement().satisfies(r -> {
                    assertThat(r.get("request_id")).isEqualTo(lia.requestId());
                    assertThat(r.get("creditor_id")).isEqualTo(creditorA);
                    assertThat(r.get("source")).isEqualTo("IMPORT");
                    assertThat(r.get("lines")).isEqualTo(2);
                    assertThat(r.get("outcome")).isNull();
                });

        // a prévia não consulta o pedido (decisão 1498): não serve para sondar documentos
        ImportBatchView preview = uploadCsv("sonda.csv", HEADER + "\n" + line(cpf(21), "Lia", "P-21", "10,00", null)
                + "\n", "VALIDATE_ONLY");
        runJobs();
        assertThat(issues(preview.id())).extracting(ImportIssueView::code)
                .doesNotContain(DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
        assertThat(count("select count(*) from compliance.erased_subject_return where batch_id = ?", preview.id()))
                .isZero();

        // cadastro manual e coobrigado manual: mesma recusa, nada criado, tentativa registrada
        assertThatThrownBy(() -> system(() -> debtors.register(new RegisterDebtorCommand(cpf(21), "Lia", null, null))))
                .hasFieldOrPropertyWithValue("code", DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
        UUID contract = system(() -> jdbcTemplate.queryForObject("select id from debt.origin_contract "
                + "where external_id = 'C-R-23'", UUID.class));
        assertThatThrownBy(() -> system(() -> parties.add(contract, new AddPartyCommand(cpf(21), "Lia", null,
                "GUARANTOR_AVAL", null))))
                .hasFieldOrPropertyWithValue("code", DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
        assertThat(count("select count(*) from debt.debtor")).isEqualTo(debtorsBefore + 1);
        assertThat(system(() -> jdbcTemplate.queryForList("select source from compliance.erased_subject_return "
                + "where request_id = ? and batch_id is null order by attempted_at", String.class, lia.requestId())))
                .containsExactly("DEBTOR_FORM", "CO_OBLIGOR_FORM");

        // o índice do pedido é de escrita única: nem bug nem chamador novo desliga a recusa
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update compliance.data_subject_request "
                + "set erased_subject_hash = null where id = ?", lia.requestId())))
                .isInstanceOf(DataAccessException.class);
        // pedido executado não pode mais ser recusado (decisão 1499)
        assertThatThrownBy(() -> system(() -> requests.reject(lia.requestId(), "Recusa depois de executar")))
                .hasFieldOrPropertyWithValue("code", "dsr.already_executed");
    }

    @Test
    void erasureInAnotherTenantDoesNotRefuseTheSameDocument() {
        importPayAndErase(cpf(31), "Teo", "R-31", null);
        // outra assessoria: o índice inclui o tenant, a mesma pessoa entra normalmente
        setUpScenario();
        ImportBatchView batch = uploadCsv("outro.csv", HEADER + "\n" + line(cpf(31), "Teo", "R-31", "10,00", null)
                + "\n", "IMPORT");
        runJobs();
        assertThat(batch(batch.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from compliance.erased_subject_return")).isZero();
    }

    @Test
    void lateReversalOnSettledTitleOfErasedSubjectKeepsItSettled() {
        Erased ana = importPayAndErase(cpf(41), "Ana", "R-41", cpf(41));
        system(() -> ledger.reversePayment(new PaymentReversal(ana.titleId(), ana.paymentId(), Instant.now(),
                "Pix devolvido")));
        Map<String, Object> title = system(() -> jdbcTemplate.queryForMap("select status, open_balance, status_reason "
                + "from debt.debt_title where id = ?", ana.titleId()));
        // o dinheiro devolvido consta no razão, mas a cobrança não reabre sem titular (decisão 1603)
        assertThat(title.get("status")).isEqualTo("SETTLED");
        assertThat((BigDecimal) title.get("open_balance")).isEqualByComparingTo("90.00");
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and action = 'debt_title.reversal_kept_settled'", ana.titleId())).isEqualTo(1);
    }

    @Test
    void creditorOfTheContractWhereTheSubjectWasCoObligorIsNotified() {
        // Bia é avalista do contrato de Caio no credor A; o título de Caio está quitado
        ImportBatchView first = uploadCsv("aval.csv", LEGAL_HEADER + "\n" + line(cpf(51), "Caio", "R-51", "70,00", null)
                + ";" + cpf(52) + ";Bia;Avalista\n", "IMPORT");
        runJobs();
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("70.00")), Instant.now())));
        UUID bia = system(() -> jdbcTemplate.queryForObject("select p.debtor_id from debt.obligation_party p "
                + "join debt.origin_contract c on c.id = p.origin_contract_id where c.external_id = 'C-R-51'", UUID.class));
        UUID request = erase(bia, cpf(52), "Bia");
        // revisão da decisão 1480 (decisão 1605): o credor do contrato recebe o aviso, citando a coobrigação
        assertThat(system(() -> jdbcTemplate.queryForList("select i.reference from compliance.creditor_erasure_notice n "
                + "join compliance.creditor_erasure_notice_item i on i.notice_id = n.id "
                + "where n.debtor_id = ? and n.creditor_id = ? and n.origin_id = ?", String.class, bia, creditorA, request)))
                .containsExactly("Contrato C-R-51 (coobrigado)");
    }

    /**
     * Corrida com a eliminação (decisões 1601 e 1602, SEG-20261126-E03): a eliminação segura o devedor até o commit; o
     * coobrigado do devedor já existente espera na trava compartilhada e vê o devedor anonimizado; o cadastro do mesmo
     * documento ainda vê o cadastro e recusa como duplicado, e depois do commit é recusado pelo pedido. Ninguém traz a
     * pessoa de volta.
     */
    @Test
    void registrationRacingWithTheErasureNeverBringsTheSubjectBack() throws Exception {
        Erased caio = importPayAndErase(cpf(61), "Caio", "R-61", null);
        ImportBatchView other = uploadCsv("dani.csv", HEADER + "\n" + line(cpf(62), "Dani", "R-62", "80,00", null)
                + "\n" + line(cpf(64), "Gil", "R-64", "10,00", null) + "\n", "IMPORT");
        runJobs();
        UUID dani = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and external_id = 'R-62'", UUID.class, other.id()));
        UUID daniTitle = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ? and external_id = 'R-62'", UUID.class, other.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(daniTitle, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("80.00")), Instant.now())));
        UUID contract = system(() -> jdbcTemplate.queryForObject("select id from debt.origin_contract "
                + "where external_id = 'C-R-64'", UUID.class));
        assertThat(caio.requestId()).isNotNull();

        java.util.concurrent.CountDownLatch erased = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            // a eliminação de Dani fica aberta (sem commit) até o sinal
            java.util.concurrent.Future<?> erasure = pool.submit(() -> system(() -> {
                RequestView opened = requests.open(new OpenRequestCommand("ANONYMIZATION", "PHONE", "Dani", null, null,
                        "Pedido do titular", null));
                requests.verifyIdentity(opened.id(), dani);
                requests.start(opened.id(), null);
                requests.execute(opened.id());
                erased.countDown();
                await(release);
                return null;
            }));
            assertThat(erased.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> register = pool.submit(() -> system(() ->
                    debtors.register(new RegisterDebtorCommand(cpf(62), "Dani", null, null))));
            java.util.concurrent.Future<?> coObligor = pool.submit(() -> system(() ->
                    parties.add(contract, new AddPartyCommand(cpf(62), "Dani", null, "GUARANTOR_AVAL", null))));
            Thread.sleep(500);
            assertThat(coObligor.isDone()).as("coobrigado espera a eliminação").isFalse();
            release.countDown();
            erasure.get(20, java.util.concurrent.TimeUnit.SECONDS);
            // o cadastro ainda vê o documento gravado (a eliminação não confirmou) e recusa como já cadastrado
            assertThatThrownBy(() -> register.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .rootCause().hasFieldOrPropertyWithValue("code", "debtor.document_taken");
            // depois da eliminação, o mesmo cadastro é recusado pelo pedido
            assertThatThrownBy(() -> system(() -> debtors.register(new RegisterDebtorCommand(cpf(62), "Dani", null, null))))
                    .hasFieldOrPropertyWithValue("code", DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
            // recusa neutra também na corrida: não diz que a pessoa foi anonimizada (SEG-20261126-E11)
            assertThatThrownBy(() -> coObligor.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .rootCause().hasFieldOrPropertyWithValue("code", DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from debt.debtor where status = 'ACTIVE' and id in (select debtor_id from "
                + "debt.obligation_party where origin_contract_id = ?)", contract)).isZero();
        assertThat(count("select count(*) from debt.debtor where status = 'ACTIVE' and created_at > now() - interval '1 minute' "
                + "and id not in (select debtor_id from ingestion.import_batch_row where debtor_id is not null)")).isZero();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Decisão 1604: a tentativa fica pendente até a revisão; manter a recusa a encerra; liberar exige outra pessoa e
     * motivo, e a partir daí a pessoa volta a ser aceita. A liberação é única e imutável.
     */
    @Test
    void returnIsReviewedAndOnlyAnotherPersonReleasesIt() {
        Erased eva = importPayAndErase(cpf(71), "Eva", "R-71", cpf(71));
        ImportBatchView first = uploadCsv("eva1.csv", HEADER + "\n" + line(cpf(71), "Eva", "N-71", "10,00", null) + "\n",
                "IMPORT");
        runJobs();
        assertThat(issues(first.id())).extracting(ImportIssueView::code).contains(DebtIngestionPort.SUBJECT_NOT_ACCEPTED);
        assertThat(system(() -> requests.search(new RequestFilter(null, null, null, null, null, true),
                PageQuery.of(0, 10)).content())).extracting(RequestView::id).containsExactly(eva.requestId());

        // manter a recusa: revisada, sai das pendências; o retorno continua recusado
        ReturnsView kept = officer(Ids.newId(), () -> returns.keep(eva.requestId()));
        assertThat(kept.refusing()).isTrue();
        assertThat(kept.attempts()).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo("KEPT");
            assertThat(r.creditorId()).isEqualTo(creditorA);
            assertThat(r.batchId()).isEqualTo(first.id());
        });
        assertThat(system(() -> requests.search(new RequestFilter(null, null, null, null, null, true),
                PageQuery.of(0, 10)).content())).isEmpty();
        assertThatThrownBy(() -> officer(Ids.newId(), () -> returns.keep(eva.requestId())))
                .hasFieldOrPropertyWithValue("code", "erased_subject_return.nothing_to_review");

        // quem executou a eliminação (aqui, o sistema) não libera; sem motivo também não
        assertThat(system(() -> returns.of(eva.requestId())).releasable()).isFalse();
        assertThatThrownBy(() -> system(() -> returns.release(eva.requestId(), "Titular voltou a contratar")))
                .hasFieldOrPropertyWithValue("code", "erased_subject_release.same_person");
        UUID officer = Ids.newId();
        assertThatThrownBy(() -> officer(officer, () -> returns.release(eva.requestId(), "curto")))
                .hasFieldOrPropertyWithValue("code", "erased_subject_release.reason_invalid");
        // escopo de um credor só: recusado (a tentativa cita credor e lote)
        assertThatThrownBy(() -> asUser(officer, java.util.EnumSet.of(Permission.DATA_SUBJECT_REQUEST_MANAGE), creditorA,
                () -> returns.of(eva.requestId()))).isInstanceOf(RuntimeException.class);

        ReturnsView released = officer(officer, () -> returns.release(eva.requestId(),
                "Titular voltou a contratar com o credor"));
        assertThat(released.refusing()).isFalse();
        assertThat(released.release().releasedBy()).isEqualTo(officer);
        assertThatThrownBy(() -> officer(Ids.newId(), () -> returns.release(eva.requestId(), "Outra liberação qualquer")))
                .hasFieldOrPropertyWithValue("code", "erased_subject_release.already_released");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from compliance.erased_subject_release "
                + "where request_id = ?", eva.requestId()))).isInstanceOf(DataAccessException.class);

        // liberada, a pessoa volta a ser aceita
        ImportBatchView again = uploadCsv("eva2.csv", HEADER + "\n" + line(cpf(71), "Eva", "N-72", "15,00", null) + "\n",
                "IMPORT");
        runJobs();
        assertThat(batch(again.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and action = 'data_subject_request.erased_subject_released'", eva.requestId())).isEqualTo(1);
    }

    /** Quem atende titulares: todos os credores e a permissão das solicitações. */
    private <T> T officer(UUID userId, java.util.function.Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "encarregado",
                java.util.EnumSet.of(Permission.DATA_SUBJECT_REQUEST_MANAGE), CreditorScope.ALL);
        return tenantExecution.callAs(user, action);
    }

    private record Erased(UUID debtorId, UUID titleId, UUID paymentId, UUID requestId) {
    }

    /** Importa o titular com um título, quita e elimina pelo pedido (com ou sem documento informado). */
    private Erased importPayAndErase(String document, String name, String externalId, String requestDocument) {
        ImportBatchView first = uploadCsv(externalId + ".csv", HEADER + "\n" + line(document, name, externalId, "90,00",
                null) + "\n", "IMPORT");
        runJobs();
        UUID debtor = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        UUID payment = Ids.newId();
        system(() -> ledger.applyPayment(new PaymentApplication(title, payment,
                ComponentAmounts.principalOnly(new BigDecimal("90.00")), Instant.now())));
        return new Erased(debtor, title, payment, erase(debtor, requestDocument, name));
    }

    private UUID erase(UUID debtorId, String requestDocument, String name) {
        RequestView executed = system(() -> {
            RequestView opened = requests.open(new OpenRequestCommand("ANONYMIZATION", "PHONE", name, requestDocument,
                    null, "Pedido do titular", null));
            requests.verifyIdentity(opened.id(), debtorId);
            requests.start(opened.id(), null);
            return requests.execute(opened.id());
        });
        assertThat(executed.executedAt()).isNotNull();
        return executed.id();
    }
}
