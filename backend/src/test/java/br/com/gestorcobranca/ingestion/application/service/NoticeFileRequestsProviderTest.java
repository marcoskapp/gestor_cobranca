package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.NewFileRequests;
import br.com.gestorcobranca.ingestion.domain.model.FileRequest;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;
import br.com.gestorcobranca.ingestion.domain.repository.FileRequestRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.SubjectBlockRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Evidência do pedido de novo arquivo no aviso ao credor (decisões 1497 e 1623). */
class NoticeFileRequestsProviderTest {

    private static final UUID NOTICE = UUID.randomUUID();
    private static final UUID CREDITOR = UUID.randomUUID();

    private final SubjectBlockRepository blocks = mock(SubjectBlockRepository.class);
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final FileRequestRepository requests = mock(FileRequestRepository.class);
    private final NoticeFileRequestsProvider provider = new NoticeFileRequestsProvider(blocks, batches, requests);

    private static SubjectBlock block(UUID batchId, UUID creditorId, int lines, long... rows) {
        SubjectBlock b = mock(SubjectBlock.class);
        when(b.getBatchId()).thenReturn(batchId);
        when(b.getCreditorId()).thenReturn(creditorId);
        when(b.getLines()).thenReturn(lines);
        List<SubjectBlock.Line> lineList = new ArrayList<>();
        for (long r : rows) {
            SubjectBlock.Line l = mock(SubjectBlock.Line.class);
            when(l.getRowNumber()).thenReturn(r);
            lineList.add(l);
        }
        when(b.getLineList()).thenReturn(lineList);
        return b;
    }

    private static ImportBatch batch(UUID id, UUID creditorId, Instant blockedAt) {
        ImportBatch b = mock(ImportBatch.class);
        when(b.getId()).thenReturn(id);
        when(b.getCreditorId()).thenReturn(creditorId);
        when(b.getBlockedAt()).thenReturn(blockedAt);
        when(b.getFileSha256()).thenReturn("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        return b;
    }

    @Test
    void otherCreditorIsLeftOutLinesAreDistinctAndPendingNewestComeFirst() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID requested = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        // o lote antigo tem o titular em dois blocos de processamento (linhas 900 e 1100) e uma repetida
        List<SubjectBlock> found = List.of(
                block(old, CREDITOR, 1, 900), block(old, CREDITOR, 2, 900, 1100), block(recent, CREDITOR, 1, 5),
                block(requested, CREDITOR, 1, 7), block(foreign, UUID.randomUUID(), 3, 1, 2, 3));
        List<ImportBatch> loaded = List.of(
                batch(old, CREDITOR, Instant.parse("2026-11-01T10:00:00Z")),
                batch(recent, CREDITOR, Instant.parse("2026-11-20T10:00:00Z")),
                batch(requested, CREDITOR, Instant.parse("2026-11-25T10:00:00Z")));
        when(blocks.findByNoticeIdOrderByCreatedAtAsc(NOTICE)).thenReturn(found);
        when(batches.findAllByIdIn(any())).thenReturn(loaded);
        FileRequest r = mock(FileRequest.class);
        when(r.getBatchId()).thenReturn(requested);
        when(r.getSentAt()).thenReturn(Instant.parse("2026-11-26T10:00:00Z"));
        when(r.getChannel()).thenReturn(FileRequest.Channel.EMAIL);
        when(requests.findByBatchIdIn(any())).thenReturn(List.of(r));

        NewFileRequests.Evidence evidence = provider.ofNotice(NOTICE, CREDITOR);

        assertThat(evidence.total()).isEqualTo(3);
        assertThat(evidence.items()).extracting(NewFileRequests.NewFileRequest::batchId)
                .containsExactly(recent, old, requested);
        assertThat(evidence.items().get(1).lines()).isEqualTo(2);
        assertThat(evidence.items().get(2).channel()).isEqualTo("EMAIL");
        assertThat(evidence.items()).noneMatch(i -> i.batchId().equals(foreign));
    }

    @Test
    void listIsCutAtTheLimitButTheTotalIsKept() {
        List<SubjectBlock> many = new ArrayList<>();
        List<ImportBatch> found = new ArrayList<>();
        for (int i = 0; i < NewFileRequests.MAX_BATCHES + 5; i++) {
            UUID id = UUID.randomUUID();
            many.add(block(id, CREDITOR, 1, i + 1));
            found.add(batch(id, CREDITOR, Instant.parse("2026-11-01T10:00:00Z").plusSeconds(i)));
        }
        when(blocks.findByNoticeIdOrderByCreatedAtAsc(NOTICE)).thenReturn(many);
        when(batches.findAllByIdIn(any())).thenReturn(found);
        when(requests.findByBatchIdIn(any())).thenReturn(List.of());

        NewFileRequests.Evidence evidence = provider.ofNotice(NOTICE, CREDITOR);

        assertThat(evidence.items()).hasSize(NewFileRequests.MAX_BATCHES);
        assertThat(evidence.total()).isEqualTo(NewFileRequests.MAX_BATCHES + 5);
    }

    @Test
    void noticeWithoutBlocksHasNoEvidence() {
        when(blocks.findByNoticeIdOrderByCreatedAtAsc(NOTICE)).thenReturn(List.of());
        assertThat(provider.ofNotice(NOTICE, CREDITOR)).isEqualTo(NewFileRequests.Evidence.none());
    }
}
