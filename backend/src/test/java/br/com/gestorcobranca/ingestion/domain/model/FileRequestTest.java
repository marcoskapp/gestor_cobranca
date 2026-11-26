package br.com.gestorcobranca.ingestion.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRequestTest {

    private static final Instant BLOCKED = Instant.parse("2026-11-26T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    private static ImportBatch batch(boolean blocked) {
        ImportBatch b = new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), FileLayout.standard(ImportFormat.CSV),
                ImportSource.CSV, ImportMode.IMPORT, new ImportBatch.StoredContent("tenants/x/imports/f.csv", "f.csv",
                "text/csv", 10, "a".repeat(64)), null, null);
        if (blocked) {
            b.markSubjectFound(BLOCKED);
        }
        return b;
    }

    @Test
    void requestNeedsABlockedBatchAndADateBetweenTheBlockAndNow() {
        Instant now = BLOCKED.plusSeconds(3600);
        assertThatThrownBy(() -> new FileRequest(batch(false), now, FileRequest.Channel.EMAIL, ACTOR, now))
                .hasFieldOrPropertyWithValue("code", "import_file_request.not_blocked");
        ImportBatch blocked = batch(true);
        assertThatThrownBy(() -> new FileRequest(blocked, BLOCKED.minusSeconds(1), FileRequest.Channel.EMAIL, ACTOR, now))
                .hasFieldOrPropertyWithValue("code", "import_file_request.sent_at_before_block");
        assertThatThrownBy(() -> new FileRequest(blocked, now.plusSeconds(61), FileRequest.Channel.EMAIL, ACTOR, now))
                .hasFieldOrPropertyWithValue("code", "import_file_request.sent_at_future");
        FileRequest ok = new FileRequest(blocked, now.plusSeconds(60), FileRequest.Channel.PHONE, ACTOR, now);
        assertThat(ok.getSentAt()).isEqualTo(now.plusSeconds(60));
        assertThat(ok.getChannel()).isEqualTo(FileRequest.Channel.PHONE);
    }
}
