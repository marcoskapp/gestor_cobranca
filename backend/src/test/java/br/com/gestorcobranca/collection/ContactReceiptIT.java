package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.PreviewCommand;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.debt.application.port.in.ContactNoveltyUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactReceiptCount;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorFilter;
import br.com.gestorcobranca.debt.application.service.DebtTestHooks;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contato novo recebido na carga (docs/tecnico/desenho/contatos-recebidos.md §7): a carga que cria o devedor não traz
 * novidade; a carga seguinte com telefone novo marca o contato, avisa collection (fila "só devedores com contato
 * novo") e campaign (segmento "com contato novo"); a ligação pelo número encerra a novidade; o reenvio confirma o
 * contato existente.
 */
class ContactReceiptIT extends CollectionTestSupport {

    private static final String CPF = "52998224725";
    private static final String OLD_PHONE = "(11) 98765-4321";
    private static final String NEW_PHONE = "(11) 97654-3210";

    @Autowired
    DebtorContactUseCases contacts;
    @Autowired
    DebtorUseCases debtors;
    @Autowired
    ContactNoveltyUseCases novelty;
    @Autowired
    SegmentUseCases segments;

    UUID firstBatch;
    UUID debtor;
    UUID caseId;

    @BeforeEach
    void setUp() {
        provision("receipt");
        firstBatch = Ids.newId();
        debtor = system(() -> ingestion.upsertDebtor(record(firstBatch, OLD_PHONE)).id());
        load(creditorA, portfolioA, debtor, "1500.00");
        caseId = activeCase(debtor, portfolioA).id();
    }

    private static DebtorRecord record(UUID batch, String... phones) {
        return new DebtorRecord(CPF, "Maria Fictícia", LocalDate.of(1985, 3, 15),
                java.util.Arrays.stream(phones).map(p -> new ContactRecord("PHONE", p, null, true)).toList(), batch);
    }

    @Test
    void newContactInALaterLoadIsHighlightedRoutedSegmentedAndEndsOnFirstCall() {
        // carga que criou o devedor: nada é novo
        assertThat(count("select count(*) from debt.debtor where id = ? and created_import_batch_id = ?", debtor,
                firstBatch)).isEqualTo(1);
        assertThat(count("select count(*) from debt.debtor_contact where debtor_id = ? and first_import_batch_id = ? "
                + "and not received_after_registration and novelty_until is null", debtor, firstBatch)).isEqualTo(1);
        assertThat(count("select count(*) from collection.collection_case where id = ? and new_contact_until is null "
                + "and debtor_person_type = 'INDIVIDUAL'", caseId)).isEqualTo(1);

        QueueView general = asManager(() -> queues.create(queue("GERAL", 10, creditorA, "PULL", null, Set.of())));
        QueueView fresh = asManager(() -> queues.create(new QueueCommand("NOVOS", "Contato novo", null, 1, creditorA,
                null, null, null, null, null, null, null, "OLDEST_OVERDUE", "PULL", null, Set.of(), null, true, null, null)));
        assertThat(fresh.newContactOnly()).isTrue();
        drainJobs();
        assertThat(count("select count(*) from collection.collection_case where id = ? and queue_id = ?", caseId,
                general.id())).isEqualTo(1);

        // segunda carga: telefone antigo reenviado e telefone novo
        UUID secondBatch = Ids.newId();
        clock.advance(Duration.ofDays(2));
        Instant received = clock.instant();
        system(() -> ingestion.upsertDebtor(record(secondBatch, OLD_PHONE, NEW_PHONE)));
        // reprocessar a mesma linha não muda nada
        system(() -> ingestion.upsertDebtor(record(secondBatch, OLD_PHONE, NEW_PHONE)));

        assertThat(count("select count(*) from debt.debtor_contact where debtor_id = ? and first_import_batch_id = ? "
                + "and received_after_registration and novelty_until is not null and first_attempt_at is null", debtor,
                secondBatch)).isEqualTo(1);
        assertThat(count("select count(*) from debt.debtor_contact where debtor_id = ? and first_import_batch_id = ? "
                + "and last_import_batch_id = ? and last_received_at > created_at", debtor, firstBatch, secondBatch))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.received_new' "
                + "and debtor_id = ? and reference_id = ? and (attributes ->> 'phone')::int = 1", debtor, secondBatch))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'debtor_contact.created' "
                + "and (after_state ->> 'receivedAfterRegistration')::boolean")).isEqualTo(1);
        assertThat(system(() -> ingestion.contactReceipt(secondBatch))).isEqualTo(new ContactReceiptCount(1, 1));
        assertThat(system(() -> ingestion.contactReceipt(firstBatch))).isEqualTo(new ContactReceiptCount(0, 0));

        // selo na ficha: novo sem tentativa; o antigo só confirmado pelo credor
        List<ContactView> listed = asManager(() -> contacts.list(debtor));
        ContactView fresher = listed.stream().filter(c -> c.receipt() != null && "NEW".equals(c.receipt().novelty()))
                .findFirst().orElseThrow();
        ContactView confirmed = listed.stream().filter(c -> !c.id().equals(fresher.id())).findFirst().orElseThrow();
        assertThat(fresher.receipt().noveltyUntil()).isBetween(received.plus(Duration.ofDays(30)),
                received.plus(Duration.ofDays(30)).plusSeconds(60));
        assertThat(confirmed.receipt().novelty()).isNull();
        assertThat(confirmed.receipt().confirmedAt()).isBetween(received, received.plusSeconds(60));

        // collection: fila do contato novo
        awaitIdle("collection");
        awaitTrue("caso com contato novo na fila própria", () -> count("select count(*) from collection.collection_case "
                + "where id = ? and new_contact_until is not null and queue_id = ?", caseId, fresh.id()) == 1);
        // campaign: segmento com contato novo
        awaitIdle("campaign");
        awaitTrue("audiência com contato novo", () -> count("select count(*) from campaign.audience_case "
                + "where case_id = ? and new_contact_until is not null and debtor_person_type = 'INDIVIDUAL'", caseId) == 1);
        assertThat(asManager(() -> segments.preview(new PreviewCommand(creditorA, null, withNewContact(true)))).cases())
                .isEqualTo(1);
        assertThat(asManager(() -> segments.preview(new PreviewCommand(creditorA, null, withNewContact(false)))).cases())
                .isZero();
        // lista de devedores: filtro "Com contato novo" e pela carga
        assertThat(asManager(() -> debtors.search(new DebtorFilter(null, null, null, true, null), PageQuery.of(0, 10)))
                .content()).extracting(DebtorUseCases.DebtorView::id).containsExactly(debtor);
        assertThat(asManager(() -> debtors.search(new DebtorFilter(null, null, null, false, firstBatch),
                PageQuery.of(0, 10))).content()).isEmpty();

        // contato de outro devedor no evento é ignorado
        UUID other = system(() -> ingestion.upsertDebtor(new DebtorRecord("11144477735", "João Fictício", null,
                List.of(), null)).id());
        CallCompleted mismatch = call(other, fresher.id(), "ANSWERED");
        publishAndAwait(mismatch, "debt.contact-usage");
        assertThat(count("select count(*) from debt.debtor_contact where id = ? and first_attempt_at is null",
                fresher.id())).isEqualTo(1);

        // ligação pelo número novo encerra a novidade
        clock.advance(Duration.ofHours(1));
        publishAndAwait(call(debtor, fresher.id(), "NO_ANSWER"), "debt.contact-usage");
        assertThat(count("select count(*) from debt.debtor_contact where id = ? and first_attempt_at is not null "
                + "and first_effective_at is null", fresher.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.first_attempt' "
                + "and debtor_id = ? and (attributes ->> 'receivedAfterRegistration')::boolean", debtor)).isEqualTo(1);
        assertThat(asManager(() -> contacts.list(debtor)).stream().filter(c -> c.id().equals(fresher.id())).findFirst()
                .orElseThrow().receipt().novelty()).isEqualTo("NEW_NO_ANSWER");
        awaitIdle("collection");
        awaitTrue("caso volta para a fila geral", () -> count("select count(*) from collection.collection_case "
                + "where id = ? and new_contact_until is null and queue_id = ?", caseId, general.id()) == 1);
        awaitIdle("campaign");
        awaitTrue("audiência sem contato novo", () -> count("select count(*) from campaign.audience_case "
                + "where case_id = ? and new_contact_until is null", caseId) == 1);
        assertThat(asManager(() -> debtors.search(new DebtorFilter(null, null, null, true, null), PageQuery.of(0, 10)))
                .content()).isEmpty();
        // o relatório da carga continua contando o contato recebido
        assertThat(asManager(() -> debtors.search(new DebtorFilter(null, null, null, false, secondBatch),
                PageQuery.of(0, 10))).content()).hasSize(1);
    }

    @Test
    void windowIsConfigurablePerTenantAndAudited() {
        assertThat(asManager(() -> novelty.settings()).windowDays()).isEqualTo(30);
        assertThat(asManager(() -> novelty.settings()).customized()).isFalse();
        assertThatThrownBy(() -> asManager(() -> novelty.updateSettings(0))).isInstanceOf(BusinessRuleException.class);
        assertThat(asManager(() -> novelty.updateSettings(10)).windowDays()).isEqualTo(10);
        assertThat(count("select count(*) from shared.audit_event where action = 'contact_novelty_settings.updated'"))
                .isEqualTo(1);
        // operador não configura (DEBTOR_MANAGE)
        assertThatThrownBy(() -> asOperator(operatorX, () -> novelty.updateSettings(20)))
                .isInstanceOf(RuntimeException.class);

        UUID secondBatch = Ids.newId();
        Instant received = clock.instant();
        system(() -> ingestion.upsertDebtor(record(secondBatch, OLD_PHONE, NEW_PHONE)));
        assertThat(count("select count(*) from debt.debtor_contact where debtor_id = ? and novelty_until between ? and ?",
                debtor, java.sql.Timestamp.from(received.plus(Duration.ofDays(10))),
                java.sql.Timestamp.from(received.plus(Duration.ofDays(10)).plusSeconds(60)))).isEqualTo(1);

        // janela vencida sem tentativa: a varredura republica e o caso deixa de ter contato novo
        awaitIdle("collection");
        awaitTrue("caso com contato novo", () -> count("select count(*) from collection.collection_case where id = ? "
                + "and new_contact_until is not null", caseId) == 1);
        clock.advance(Duration.ofDays(11));
        assertThat(asManager(() -> debtors.search(new DebtorFilter(null, null, null, true, null), PageQuery.of(0, 10)))
                .content()).isEmpty();
        assertThat(DebtTestHooks.sweepContactNovelty(applicationContext, tenant)).isEqualTo(1);
        awaitIdle("collection");
        awaitTrue("caso sem contato novo", () -> count("select count(*) from collection.collection_case where id = ? "
                + "and new_contact_until is null", caseId) == 1);
    }

    private CallCompleted call(UUID debtorId, UUID contactId, String result) {
        return new CallCompleted(Ids.newId(), tenant, clock.instant(), Ids.newId(), caseId, debtorId, creditorA, operatorX,
                "OUTBOUND", result, 0, portfolioA, contactId);
    }

    private static SegmentCriteria withNewContact(boolean value) {
        return new SegmentCriteria(null, null, null, null, null, null, false, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, value, null);
    }
}
