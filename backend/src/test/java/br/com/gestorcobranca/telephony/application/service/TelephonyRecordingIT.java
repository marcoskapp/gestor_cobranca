package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.telephony.TelephonyTestSupport;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gravações: aviso de gravação, cópia para o armazenamento próprio, acesso com permissão
 * específica, finalidade e log de acesso; descarte pela retenção (CALL_RECORDINGS) e pela
 * anonimização do devedor.
 */
class TelephonyRecordingIT extends TelephonyTestSupport {

    @Autowired
    TelephonyMaintenance maintenance;
    @Autowired
    RetentionUseCases retention;

    UUID debtor;
    CallView call;

    @BeforeEach
    void setUp() {
        provision("trc", true);
        debtor = debtor("529.982.247-25");
        CaseView kase = loadCase(creditorA, portfolioA, debtor, "500.00");
        asOperator(operatorX, () -> agents.login(null));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        event(queued.id(), "ANSWERED", "HUMAN", null);
        event(queued.id(), "COMPLETED", null, "DEBTOR");
        assertThat(event(queued.id(), "RECORDING_AVAILABLE", null, null)).isEqualTo("applied");
        assertThat(event(queued.id(), "RECORDING_AVAILABLE", null, null)).isEqualTo("stale");
        drainTelephony();
        call = call(queued.id());
    }

    @Test
    void recordingIsStoredAnnouncedAndAccessedWithPermissionPurposeAndAccessLog() throws IOException {
        assertThat(call.recordingAnnounced()).isTrue();
        assertThat(call.recording().status()).isEqualTo("STORED");
        assertThat(call.recording().announced()).isTrue();
        String key = string("select file_key from telephony.call_recording where call_id = ?", call.id());
        assertThat(key).startsWith("tenants/" + tenant + "/call-recordings/");
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.recording_stored'"))
                .isEqualTo(1);

        // operador sem a permissão específica
        assertThatThrownBy(() -> asOperator(operatorX, () -> calls.openRecording(call.id(), "QUALITY_MONITORING")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' "
                + "and details ->> 'permission' = 'CALL_RECORDING_LISTEN'")).isEqualTo(1);
        assertThatThrownBy(() -> asSupervisor(() -> calls.openRecording(call.id(), "CURIOSIDADE")))
                .hasMessageContaining("Finalidade");

        RecordingFile file = asSupervisor(() -> calls.openRecording(call.id(), "QUALITY_MONITORING"));
        try (InputStream in = file.content()) {
            byte[] header = in.readNBytes(4);
            assertThat(new String(header)).isEqualTo("RIFF");
        }
        assertThat(file.contentType()).isEqualTo("audio/wav");
        assertThat(file.sha256()).hasSize(64);
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' "
                + "and actor_id = ? and details ->> 'resource' = 'call_recording' "
                + "and details ->> 'purpose' = 'QUALITY_MONITORING' and details ->> 'callId' = ?",
                supervisor, call.id().toString())).isEqualTo(1);
        assertThat(count("select access_count from telephony.call_recording where call_id = ?", call.id())).isEqualTo(1);
    }

    @Test
    void retentionPolicyAndAnonymizationDiscardTheFileKeepingMetadata() {
        system(() -> retention.configure("CALL_RECORDINGS", 6, true));
        assertThat(system(maintenance::purgeRecordings)).isZero();
        system(() -> jdbcTemplate.update("update telephony.call_recording set stored_at = ? where call_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(400L * 24 * 3600)), call.id()));
        assertThat(system(maintenance::purgeRecordings)).isEqualTo(1);
        CallView purged = call(call.id());
        assertThat(purged.recording().status()).isEqualTo("PURGED");
        assertThat(purged.recording().purgeReason()).isEqualTo("RETENTION");
        assertThat(count("select count(*) from telephony.call_recording where call_id = ? and file_key is null",
                call.id())).isEqualTo(1);
        assertThatThrownBy(() -> asSupervisor(() -> calls.openRecording(call.id(), "AUDIT")))
                .hasMessageContaining("descartada");

        // anonimização do devedor: número da chamada eliminado
        DebtorAnonymized anonymized = new DebtorAnonymized(Ids.newId(), tenant, Instant.now(), debtor);
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(anonymized));
        awaitTrue("anonimização na telefonia", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'telephony.debtor-anonymized' and event_id = ?", anonymized.eventId()) == 1);
        assertThat(count("select count(*) from telephony.phone_call where id = ? and number_encrypted is null "
                + "and number_hash is null and number_masked = '***'", call.id())).isEqualTo(1);
    }

    @Test
    void recordingOfACallOutsideTheScopeIsNotFound() {
        assertThatThrownBy(() -> as(supervisor, "SUPERVISOR",
                br.com.gestorcobranca.shared.security.CreditorScope.of(java.util.Set.of(creditorB)),
                () -> calls.openRecording(call.id(), "AUDIT")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
