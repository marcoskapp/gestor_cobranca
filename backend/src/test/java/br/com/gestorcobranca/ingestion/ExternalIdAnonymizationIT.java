package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportSubjectBlockUseCases;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Identificador com CPF/CNPJ trocado por {@code anon:} + HMAC na anonimização (SEG-20261125-K02, decisão 1476): a
 * detecção nos arquivos acha as linhas pela chave da linha, a carga continua recusando o título trocado, nenhuma tela
 * mostra a forma anonimizada, o banco só aceita a troca de ida e os jobs preenchem o que veio de antes da versão.
 */
class ExternalIdAnonymizationIT extends IngestionTestSupport {

    @Autowired
    DebtLedgerPort ledger;
    @Autowired
    DataSubjectRequestUseCases requests;
    @Autowired
    ExternalIdDigest digest;
    @Autowired
    CreditorErasureNoticeUseCases notices;
    @Autowired
    ImportSubjectBlockUseCases subjectBlocks;

    @Test
    void titleWithTheDocumentIsReplacedAndItsLinesAreStillFoundAndRefused() {
        String raw = cpf(31) + "-001";
        ImportBatchView first = uploadCsv("a1.csv", HEADER + "\n" + line(cpf(31), "Iara", raw, "90,00", null) + "\n",
                "IMPORT");
        runJobs();
        UUID iara = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        String key = digest.key(tenant, Kind.TITLE, creditorA, raw);
        // a linha guarda a chave do título desde a carga
        assertThat(system(() -> jdbcTemplate.queryForObject("select title_key from ingestion.import_batch_row "
                + "where batch_id = ?", String.class, first.id()))).isEqualTo(key);
        // prévia com o título dela: a linha não grava devedor
        ImportBatchView preview = uploadCsv("a2.csv", HEADER + "\n" + line(cpf(31), "Iara", raw, "90,00", null)
                + "\n" + line(cpf(32), "Davi", "R-32", "10,00", null) + "\n", "VALIDATE_ONLY");
        runJobs();

        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("90.00")), Instant.now())));
        system(() -> {
            var opened = requests.open(new OpenRequestCommand("ANONYMIZATION", "EMAIL", "Iara", cpf(31), null,
                    "Pedido do titular", null));
            requests.verifyIdentity(opened.id(), iara);
            requests.start(opened.id(), null);
            return requests.execute(opened.id());
        });

        // o título e o contrato trocados, com a forma mascarada para exibição
        String masked = Document.maskIfDocument(raw);
        assertThat(masked).doesNotContain(cpf(31));
        assertThat(system(() -> jdbcTemplate.queryForMap(
                "select external_id, external_id_display from debt.debt_title where id = ?", title)))
                .containsEntry("external_id", "anon:" + key).containsEntry("external_id_display", masked);
        assertThat(system(() -> jdbcTemplate.queryForMap("select external_id, external_id_display "
                + "from debt.origin_contract where debtor_id = ?", iara)))
                .containsEntry("external_id", digest.anonymized(tenant, Kind.CONTRACT, creditorA, "C-" + raw))
                .containsEntry("external_id_display", Document.maskIfDocument("C-" + raw));
        // a tela mostra a forma mascarada; quem consulta não acha pelo cru nem pela forma anonimizada
        assertThat(system(() -> titles.get(title)).externalId()).isEqualTo(masked);
        assertThat(system(() -> titles.findByExternalId(creditorA, raw))).isEmpty();
        assertThat(system(() -> titles.findByExternalId(creditorA, "anon:" + key))).isEmpty();
        assertThat(system(() -> titles.findByExternalId(creditorA, "\u2003anon:" + key))).isEmpty();
        // auditoria só com quantidades
        assertThat(system(() -> jdbcTemplate.queryForObject("select (before_state::text || after_state::text || metadata::text) from shared.audit_event "
                + "where action = 'debtor.anonymized' and entity_id = ?", String.class, iara)))
                .contains("titleIdsAnonymized").doesNotContain(cpf(31)).doesNotContain(key);

        // a detecção assíncrona acha a linha da prévia pela chave (o identificador cru não existe mais no debt)
        long deadline = System.currentTimeMillis() + 15_000;
        while (count("select count(*) from ingestion.import_subject_block where batch_id = ?", preview.id()) == 0) {
            assertThat(System.currentTimeMillis()).as("bloqueio da prévia").isLessThan(deadline);
            sleep();
        }
        assertThat(system(() -> jdbcTemplate.queryForList("select l.row_number, l.matched_by from "
                + "ingestion.import_subject_block b join ingestion.import_subject_block_line l on l.block_id = b.id "
                + "where b.batch_id = ?", preview.id()))).singleElement().satisfies(r -> {
                    assertThat(r.get("row_number")).isEqualTo(2L);
                    assertThat(r.get("matched_by")).isEqualTo("TITLE_REFERENCE");
                });

        // evidência do pedido de novo arquivo no aviso ao credor (decisão 1497): os dois lotes bloqueados, sem pedido
        UUID noticeId = system(() -> jdbcTemplate.queryForObject("select id from compliance.creditor_erasure_notice "
                + "where debtor_id = ? and creditor_id = ?", UUID.class, iara, creditorA));
        var notice = system(() -> notices.get(noticeId));
        assertThat(notice.fileRequests()).extracting(f -> f.batchId()).containsExactlyInAnyOrder(first.id(), preview.id());
        assertThat(notice.fileRequestsTotal()).isEqualTo(2);
        assertThat(notice.fileRequests()).allSatisfy(f -> {
            assertThat(f.sentAt()).isNull();
            assertThat(f.lines()).isEqualTo(1);
            String sha = system(() -> jdbcTemplate.queryForObject("select file_sha256 from ingestion.import_batch "
                    + "where id = ?", String.class, f.batchId()));
            assertThat(f.protocol()).isEqualTo(sha.substring(0, f.protocol().length())).hasSizeGreaterThan(4);
        });
        // registrado na importação: aparece no aviso, com canal e autor
        asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(first.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(Instant.now(), "EMAIL")));
        var after = system(() -> notices.get(noticeId)).fileRequests();
        // o que ainda pede ação vem primeiro
        assertThat(after).extracting(f -> f.batchId()).containsExactly(preview.id(), first.id());
        assertThat(after.get(1)).satisfies(f -> {
            assertThat(f.sentAt()).isNotNull();
            assertThat(f.channel()).isEqualTo("EMAIL");
            assertThat(f.sentBy()).isNotNull();
        });

        // a mesma linha numa carga nova: recusada, sem título novo com o identificador cru
        ImportBatchView again = uploadCsv("a3.csv", HEADER + "\n" + line(cpf(31), "Iara", raw, "90,00", null) + "\n"
                + line(cpf(33), "Rui", "R-33", "10,00", null) + "\n", "IMPORT");
        runJobs();
        assertThat(issues(again.id())).anySatisfy(i -> assertThat(i.rowNumber()).isEqualTo(2L));
        assertThat(count("select count(*) from debt.debt_title where external_id = ?", raw)).isZero();
        assertThat(count("select count(*) from debt.origin_contract where external_id = ?", "C-" + raw)).isZero();
        assertThat(count("select count(*) from debt.debt_title where creditor_id = ? and external_id = 'R-33'",
                creditorA)).isEqualTo(1);
    }

    @Test
    void databaseAcceptsOnlyTheOneWayReplacementAndTheReservedPrefixIsRefused() {
        ImportBatchView first = uploadCsv("b1.csv", HEADER + "\n" + line(cpf(41), "Lia", "R-41", "10,00", null) + "\n"
                + line(cpf(42), "Ana", "anon:" + "0".repeat(64), "10,00", null) + "\n", "IMPORT");
        runJobs();
        // prefixo reservado: erro da linha, sem falar de anonimização
        assertThat(issues(first.id())).anySatisfy(i -> {
            assertThat(i.rowNumber()).isEqualTo(3L);
            assertThat(i.code()).isEqualTo("row.external_id_reserved");
        });
        UUID title = system(() -> jdbcTemplate.queryForObject("select id from debt.debt_title "
                + "where creditor_id = ? and external_id = 'R-41'", UUID.class, creditorA));
        // inclusão já trocada: recusada pelo banco (a troca é só de ida, sobre registro existente)
        assertThatThrownBy(() -> system(() -> {
            jdbcTemplate.execute("create temp table copia on commit drop as select * from debt.debt_title where false");
            jdbcTemplate.update("insert into copia select * from debt.debt_title where id = ?", title);
            jdbcTemplate.update("update copia set id = gen_random_uuid(), external_id = ?, external_id_display = 'x'",
                    "anon:" + "2".repeat(64));
            return jdbcTemplate.update("insert into debt.debt_title select * from copia");
        })).rootCause().hasMessageContaining("só por troca");
        // devedor ativo: nem a troca de ida é aceita
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update debt.debt_title set external_id = ?, "
                + "external_id_display = 'R-41' where id = ?", "anon:" + "1".repeat(64), title)))
                .hasMessageContaining("devedor anonimizado");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update debt.debt_title set external_id = 'R-41b' where id = ?", title)))
                .hasMessageContaining("forma anonimizada");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update debt.debt_title set external_id_display = 'x' where id = ?", title)))
                .hasMessageContaining("só na troca");
        // a linha da carga continua imutável: a aplicação só pode preencher a chave que falta (privilégio por coluna)
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update ingestion.import_batch_row set external_id = 'R-41c' where batch_id = ?", first.id())))
                .rootCause().hasMessageContaining("permission denied");
        // e a chave já gravada não muda (gatilho)
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update ingestion.import_batch_row set title_key = ? where batch_id = ?", "f".repeat(64), first.id())))
                .hasMessageContaining("imutável");
    }

    @Test
    void backfillJobReplacesIdsAnonymizedBeforeTheVersion() {
        String raw = cpf(51) + "/7";
        ImportBatchView first = uploadCsv("c1.csv", HEADER + "\n" + line(cpf(51), "Teo", raw, "10,00", null) + "\n",
                "IMPORT");
        runJobs();
        UUID teo = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("10.00")), Instant.now())));
        // como a anonimização de antes da versão: devedor anonimizado com o identificador cru
        system(() -> jdbcTemplate.update("update debt.debtor set status = 'ANONYMIZED', document_encrypted = null, "
                + "document_hash = null, document_root_hash = null, anonymized_at = now() where id = ?", teo));
        // linha nova sem a chave do título é recusada pelo banco (um caminho de gravação que a esqueça falha); o
        // preenchimento das anteriores à V2309 tem teste unitário (RowTitleKeyBackfillJobTest)
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                insert into ingestion.import_batch_row (id, tenant_id, version, created_at, updated_at, batch_id,
                       external_id, row_number, warning_count, title_suspended)
                values (?, ?, 0, now(), now(), ?, 'LEGADO-1', 99, 0, false)
                """, Ids.newId(), tenant, first.id()))).rootCause()
                .hasMessageContaining("ck_import_batch_row_title_key_present");

        runHandler("debt.external-id-anonymization");

        String key = digest.key(tenant, Kind.TITLE, creditorA, raw);
        assertThat(system(() -> jdbcTemplate.queryForObject("select external_id from debt.debt_title where id = ?",
                String.class, title))).isEqualTo("anon:" + key);
        assertThat(count("select count(*) from shared.audit_event where action = 'debtor.external_ids_anonymized' "
                + "and entity_id = ?", teo)).isEqualTo(1);
        // de novo: nada muda
        Object version = system(() -> jdbcTemplate.queryForObject("select version from debt.debt_title where id = ?",
                Long.class, title));
        runHandler("debt.external-id-anonymization");
        assertThat(system(() -> jdbcTemplate.queryForObject("select version from debt.debt_title where id = ?",
                Long.class, title))).isEqualTo(version);
        assertThat(count("select count(*) from shared.audit_event where action = 'debtor.external_ids_anonymized' "
                + "and entity_id = ?", teo)).isEqualTo(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runHandler(String type) {
        JobHandler handler = jobHandlers.stream().filter(h -> h.type().equals(type)).findFirst().orElseThrow();
        Object payload = json.fromJson("{}", handler.payloadType());
        tenantExecution.inTenant(tenant, "job:" + type,
                () -> handler.handle(new JobContext(Ids.newId(), tenant, type, 1), payload));
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
