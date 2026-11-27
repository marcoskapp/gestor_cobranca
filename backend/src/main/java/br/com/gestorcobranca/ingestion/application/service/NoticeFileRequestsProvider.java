package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.NewFileRequests;
import br.com.gestorcobranca.ingestion.domain.model.FileRequest;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;
import br.com.gestorcobranca.ingestion.domain.repository.FileRequestRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.SubjectBlockRepository;
import br.com.gestorcobranca.ingestion.domain.service.CreditorFileRequestText;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evidência do pedido de novo arquivo para o aviso da eliminação ao credor (decisões 1497 e 1623): os lotes do credor
 * bloqueados com o aviso ({@code import_subject_block.notice_id}) e o registro do pedido de cada um. Só leitura, na
 * transação do compliance, que já conferiu o acesso ao aviso; o credor do aviso também filtra os lotes (um aviso é de
 * um credor só). Sem nome do arquivo nem linhas: protocolo, quantidade e o registro.
 */
@Component
class NoticeFileRequestsProvider implements NewFileRequests {

    /** Os que pedem ação primeiro (sem pedido), do mais recente ao mais antigo; depois os com pedido. */
    private static final Comparator<Candidate> ORDER = Comparator
            .comparing((Candidate c) -> c.request() != null)
            .thenComparing((Candidate c) -> c.batch().getBlockedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(c -> c.batch().getId());

    private record Candidate(ImportBatch batch, FileRequest request) {
    }

    private final SubjectBlockRepository subjectBlocks;
    private final ImportBatchRepository batches;
    private final FileRequestRepository requests;

    NoticeFileRequestsProvider(SubjectBlockRepository subjectBlocks, ImportBatchRepository batches,
                               FileRequestRepository requests) {
        this.subjectBlocks = subjectBlocks;
        this.batches = batches;
        this.requests = requests;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Evidence ofNotice(UUID noticeId, UUID creditorId) {
        Objects.requireNonNull(noticeId, "noticeId");
        Objects.requireNonNull(creditorId, "creditorId");
        Map<UUID, List<SubjectBlock>> blocksByBatch = new LinkedHashMap<>();
        for (SubjectBlock block : subjectBlocks.findByNoticeIdOrderByCreatedAtAsc(noticeId)) {
            if (creditorId.equals(block.getCreditorId())) {
                blocksByBatch.computeIfAbsent(block.getBatchId(), k -> new ArrayList<>()).add(block);
            }
        }
        if (blocksByBatch.isEmpty()) {
            return Evidence.none();
        }
        List<ImportBatch> found = batches.findAllByIdIn(blocksByBatch.keySet()).stream()
                .filter(b -> creditorId.equals(b.getCreditorId())).toList();
        Map<UUID, FileRequest> requestByBatch = found.isEmpty() ? Map.of()
                : requests.findByBatchIdIn(found.stream().map(ImportBatch::getId).toList()).stream()
                .collect(Collectors.toMap(FileRequest::getBatchId, Function.identity(), (a, b) -> a));
        List<NewFileRequest> items = found.stream()
                .map(b -> new Candidate(b, requestByBatch.get(b.getId())))
                .sorted(ORDER)
                .limit(MAX_BATCHES)
                .map(c -> {
                    ImportBatch b = c.batch();
                    FileRequest r = c.request();
                    return new NewFileRequest(b.getId(), CreditorFileRequestText.protocol(b.getFileSha256()),
                            b.getBlockedAt(), SubjectBlock.linesOf(blocksByBatch.get(b.getId())), b.isInProgress(),
                            r == null ? null : r.getSentAt(), r == null ? null : r.getChannel().name(),
                            r == null ? null : r.getSentBy());
                })
                .toList();
        return new Evidence(items, found.size());
    }
}
