package br.com.gestorcobranca.shared.privacy;

import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSharingFactTest {

    @Test
    void keyIsModuleNameWithOptionalId() {
        UUID id = UUID.randomUUID();
        assertThat(new DataSharingFact(DataRecipientType.LAW_FIRM, "legal.law_firm:" + id, id, "Advocacia",
                DataSharingPurpose.LEGAL_COLLECTION, Instant.now(), Instant.now(), 1, "1 caso", null).recipientKey())
                .endsWith(id.toString());
        assertThatThrownBy(() -> new DataSharingFact(DataRecipientType.PROVIDER, "Twilio Inc", null, "Twilio",
                DataSharingPurpose.PHONE_CALLS, null, null, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(DataSharingFact.counted(1, "mensagem", "mensagens")).isEqualTo("1 mensagem");
        assertThat(DataSharingFact.counted(3, "mensagem", "mensagens")).isEqualTo("3 mensagens");
    }

    @Test
    void linkCarriesOnlyIdsAndCodesAndThePermissionOfTheTarget() {
        DataSharingLink link = DataSharingLink.to("/telefonia/chamadas", "Ver chamadas", Permission.TELEPHONY_SUPERVISE)
                .withParam("devedor", UUID.randomUUID().toString());
        assertThat(link.anyPermission()).containsExactly(Permission.TELEPHONY_SUPERVISE);
        assertThatThrownBy(() -> link.withParam("documento", "123.456.789-09"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataSharingLink("/x?y=1", Map.of(), Set.of(Permission.INBOX_VIEW), Set.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataSharingLink("/x", Map.of(), Set.of(), Set.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
