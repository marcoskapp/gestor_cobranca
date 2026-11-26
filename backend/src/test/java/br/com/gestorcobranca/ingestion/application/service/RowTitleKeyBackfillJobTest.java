package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.KeylessRow;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.crypto.CryptoProperties;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Preenchimento da chave do título nas linhas anteriores à V2309 (decisões 1476 e 1617; SEG-20261126-X11). */
class RowTitleKeyBackfillJobTest {

    private static final String K1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String BLIND = Base64.getEncoder().encodeToString("blind-index-key-with-32-bytes!!!".getBytes());
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CREDITOR = UUID.randomUUID();

    private final ExternalIdDigest digest =
            new ExternalIdDigest(new BlindIndex(new CryptoProperties(Map.of("k1", K1), "k1", BLIND)));
    private final ImportFileRetentionStore store = mock(ImportFileRetentionStore.class);
    private final JobQueue queue = mock(JobQueue.class);
    private final RowTitleKeyBackfillJob job = new RowTitleKeyBackfillJob(store, digest, queue);

    @Test
    void fillsTheKeysSkipsRowsWithoutCanonicalFormAndContinuesByCursor() {
        List<KeylessRow> rows = new ArrayList<>();
        for (int i = 0; i < RowTitleKeyBackfillJob.BATCH; i++) {
            rows.add(new KeylessRow(new UUID(0L, i + 1L), CREDITOR, i == 0 ? "   " : "T-" + i));
        }
        when(store.rowsWithoutTitleKey(null, RowTitleKeyBackfillJob.BATCH)).thenReturn(rows);
        when(store.assignTitleKeys(any())).thenAnswer(a -> ((Map<?, ?>) a.getArgument(0)).size());

        job.handle(new JobContext(UUID.randomUUID(), TENANT, RowTitleKeyBackfillJob.TYPE, 1),
                new RowTitleKeyBackfillJob.Payload(null));

        verify(store).assignTitleKeys(org.mockito.ArgumentMatchers.argThat(keys -> keys.size() == rows.size() - 1
                && !keys.containsKey(rows.getFirst().id())
                && digest.key(TENANT, Kind.TITLE, CREDITOR, "T-1").equals(keys.get(rows.get(1).id()))));
        UUID last = rows.getLast().id();
        verify(queue).enqueue(eq(RowTitleKeyBackfillJob.TYPE), eq(new RowTitleKeyBackfillJob.Payload(last)), eq(null),
                eq(RowTitleKeyBackfillJob.TYPE + ":" + last));
    }

    @Test
    void partialBatchEndsWithoutContinuation() {
        UUID after = UUID.randomUUID();
        when(store.rowsWithoutTitleKey(after, RowTitleKeyBackfillJob.BATCH))
                .thenReturn(List.of(new KeylessRow(UUID.randomUUID(), CREDITOR, "T-1")));
        job.handle(new JobContext(UUID.randomUUID(), TENANT, RowTitleKeyBackfillJob.TYPE, 1),
                new RowTitleKeyBackfillJob.Payload(after));
        verify(queue, never()).enqueue(any(), any(), any(), any());
        assertThat(after).isNotNull();
    }
}
