package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Channel;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Origin;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditorErasureNoticeTest {

    private static final Instant ERASED = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private final UUID by = Ids.newId();

    private CreditorErasureNotice notice() {
        return new CreditorErasureNotice(Ids.newId(), Ids.newId(), Origin.DATA_SUBJECT_REQUEST, Ids.newId(), ERASED,
                List.of(new Item(Ids.newId(), "R-7", 1)));
    }

    @Test
    void opensPendingAndRequiresAtLeastOneTitle() {
        assertThat(notice().getStatus()).isEqualTo(Status.PENDING);
        assertThatThrownBy(() -> new CreditorErasureNotice(Ids.newId(), Ids.newId(), Origin.RETENTION, Ids.newId(),
                ERASED, List.of())).hasFieldOrPropertyWithValue("code", "creditor_erasure_notice.items_required");
        assertThatThrownBy(() -> new Item(Ids.newId(), "x".repeat(CreditorErasureNotice.MAX_REFERENCE + 1), null))
                .hasFieldOrPropertyWithValue("code", "creditor_erasure_notice.reference_invalid");
    }

    @Test
    void registersTheNoticeOnceWithinTheErasureAndNow() {
        CreditorErasureNotice notice = notice();
        assertThatThrownBy(() -> notice.registerNotified(NOW.plusSeconds(3600), Channel.EMAIL, by, NOW))
                .hasFieldOrPropertyWithValue("code", "creditor_erasure_notice.notified_at_future");
        assertThatThrownBy(() -> notice.registerNotified(ERASED.minusSeconds(1), Channel.EMAIL, by, NOW))
                .hasFieldOrPropertyWithValue("code", "creditor_erasure_notice.notified_at_before_erasure");
        Instant notifiedAt = NOW.minusSeconds(86_400);
        notice.registerNotified(notifiedAt, Channel.PHONE, by, NOW);
        assertThat(notice.getStatus()).isEqualTo(Status.NOTIFIED);
        assertThat(notice.getNotifiedAt()).isEqualTo(notifiedAt);
        assertThat(notice.getNotifiedChannel()).isEqualTo(Channel.PHONE);
        assertThat(notice.getNotifiedBy()).isEqualTo(by);
        assertThat(notice.getRegisteredAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> notice.registerNotified(NOW, Channel.EMAIL, by, NOW))
                .hasFieldOrPropertyWithValue("code", "creditor_erasure_notice.already_notified");
    }

    /** Decisão 1605: o contrato em que o titular era coobrigado vira item marcado, sem passar do limite do item. */
    @Test
    void coObligorContractItemIsMarkedAndNeverExceedsTheLimit() {
        Item item = new Item(Ids.newId(), CreditorErasureNotice.coObligorReference("C-9"), null);
        assertThat(item.getReference()).isEqualTo("Contrato C-9 (coobrigado)");
        assertThat(item.isCoObligorContract()).isTrue();
        assertThat(new Item(Ids.newId(), "C-9", 1).isCoObligorContract()).isFalse();
        String longest = "x".repeat(CreditorErasureNotice.MAX_REFERENCE);
        assertThat(CreditorErasureNotice.coObligorReference(longest)).isEqualTo(longest);
    }
}
