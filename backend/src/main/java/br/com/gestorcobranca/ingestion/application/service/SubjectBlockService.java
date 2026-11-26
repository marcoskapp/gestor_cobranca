package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeStatusQueries;
import br.com.gestorcobranca.ingestion.application.port.in.ImportSubjectBlockUseCases;
import br.com.gestorcobranca.ingestion.domain.model.FileRequest;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;
import br.com.gestorcobranca.ingestion.domain.repository.FileRequestRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.SubjectBlockRepository;
import br.com.gestorcobranca.ingestion.domain.service.CreditorFileRequestText;
import br.com.gestorcobranca.ingestion.domain.service.CreditorFileRequestText.RequestLine;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Lote bloqueado por titular anonimizado: pedido de novo arquivo ao credor (decisão 1497). */
@Service
@Transactional
class SubjectBlockService implements ImportSubjectBlockUseCases {

    private static final String RESOURCE = BatchCreation.RESOURCE;
    /** Linhas listadas no texto e na tela (o registro guarda até 500 por bloqueio). */
    static final int MAX_LINES = 500;

    private final ImportBatchRepository batches;
    private final SubjectBlockRepository subjectBlocks;
    private final FileRequestRepository requests;
    private final CreditorErasureNoticeStatusQueries notices;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    SubjectBlockService(ImportBatchRepository batches, SubjectBlockRepository subjectBlocks,
                        FileRequestRepository requests, CreditorErasureNoticeStatusQueries notices,
                        CreditorUseCases creditors, AccessScope accessScope, CurrentActor currentActor,
                        AuditTrail auditTrail, BusinessEventLogger businessEvents, Clock clock) {
        this.batches = batches;
        this.subjectBlocks = subjectBlocks;
        this.requests = requests;
        this.notices = notices;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SubjectBlockView forBatch(UUID batchId) {
        accessScope.requirePermission(Permission.IMPORT_VIEW);
        return view(requireBlocked(batches.findById(batchId).orElse(null), batchId));
    }

    @Override
    public SubjectBlockView registerRequestSent(UUID batchId, RegisterRequestCommand command) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        // a resposta é a mesma tela da leitura (inclui a situação do aviso, que exige IMPORT_VIEW)
        accessScope.requirePermission(Permission.IMPORT_VIEW);
        // travado: dois registros simultâneos não passam juntos
        ImportBatch batch = requireBlocked(batches.findByIdForUpdate(batchId).orElse(null), batchId);
        accessScope.requireCreditor(batch.getCreditorId());
        // em andamento a lista de linhas ainda pode crescer: o pedido registrado (imutável) ficaria incompleto
        Guard.requireState(!batch.isInProgress(), "import_file_request.batch_in_progress",
                "O lote ainda está em processamento: aguarde o fim para pedir o novo arquivo");
        if (requests.findByBatchId(batchId).isPresent()) {
            throw new ConflictException("import_file_request.already_registered",
                    "O pedido de novo arquivo deste lote já foi registrado");
        }
        FileRequest.Channel channel = parseChannel(command == null ? null : command.channel());
        FileRequest request = requests.save(new FileRequest(batch, command.sentAt(), channel, currentActor.get().id(),
                Instant.now(clock)));
        auditTrail.record(AuditEntry.of("import_batch.file_request_sent", RESOURCE, batchId)
                .creditor(batch.getCreditorId())
                .created("sentAt", request.getSentAt())
                .created("channel", channel)
                .meta("requestId", request.getId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_FILE_REQUEST_SENT)
                .creditor(batch.getCreditorId()).portfolio(batch.getPortfolioId())
                .reference("import_batch", batchId)
                .channel(channel.name())
                .durationMs(Duration.between(batch.getBlockedAt(), request.getSentAt()).toMillis()));
        return view(batch);
    }

    /** Fora do escopo ou não bloqueado: 404 (anti-enumeração — não revela que o lote existe). */
    private ImportBatch requireBlocked(ImportBatch batch, UUID batchId) {
        if (batch == null) {
            throw ResourceNotFoundException.of(RESOURCE, batchId);
        }
        // fora do escopo: 404 com o registro da negação (SCOPE_DENIED)
        accessScope.requireVisible(RESOURCE, batchId, batch.getCreditorId());
        if (!batch.isBlocked()) {
            throw ResourceNotFoundException.of(RESOURCE, batchId);
        }
        return batch;
    }

    private static FileRequest.Channel parseChannel(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("import_file_request.channel_required", "Informe o canal do pedido");
        }
        try {
            return FileRequest.Channel.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("import_file_request.channel_invalid", "Canal do pedido inválido");
        }
    }

    private SubjectBlockView view(ImportBatch batch) {
        List<SubjectBlock> blocks = subjectBlocks.findByBatchIdOrderByCreatedAtAsc(batch.getId());
        // linhas de todos os registros do lote (anonimização e carga), uma por número de linha
        Map<Long, SubjectBlock.Line> byRow = new TreeMap<>();
        UUID noticeId = null;
        for (SubjectBlock block : blocks) {
            block.getLineList().forEach(l -> byRow.putIfAbsent(l.getRowNumber(), l));
            if (noticeId == null) {
                noticeId = block.getNoticeId();
            }
        }
        int listed = Math.min(byRow.size(), MAX_LINES);
        int linesTotal = Math.max(byRow.size(), blocks.stream().mapToInt(SubjectBlock::getLines).max().orElse(0));
        List<BlockLineView> lines = new ArrayList<>();
        List<RequestLine> textLines = new ArrayList<>();
        byRow.values().stream().limit(listed).forEach(l -> {
            lines.add(new BlockLineView(l.getRowNumber(), l.getReference(), l.getMatchedBy().name()));
            textLines.add(new RequestLine(l.getRowNumber(), l.getReference()));
        });
        String creditorName = creditors.getAll(List.of(batch.getCreditorId())).stream().findFirst()
                .map(SubjectBlockService::displayName).orElse("credor");
        LocalDate sentOn = LocalDate.ofInstant(batch.getCreatedAt(), TimeConfig.BUSINESS_ZONE);
        // o nome do arquivo vem do credor: mascarado e sem caracteres de controle, como as referências (M15)
        String fileName = batch.getFileName() == null ? "registros via API" : SubjectBlock.displayReference(batch.getFileName());
        String message = CreditorFileRequestText.compose(creditorName, fileName, sentOn,
                batch.getFileSha256(), textLines, linesTotal - listed);
        NoticeSituation notice = noticeId == null ? null : notices.statusOf(noticeId, batch.getCreditorId())
                .map(n -> new NoticeSituation(n.id(), n.status(), n.notifiedAt())).orElse(null);
        RequestView request = requests.findByBatchId(batch.getId())
                .map(r -> new RequestView(r.getSentAt(), r.getChannel().name(), r.getSentBy(), r.getCreatedAt()))
                .orElse(null);
        return new SubjectBlockView(batch.getId(), batch.getBlockedAt(), batch.getFileName(), batch.getCreatedAt(),
                CreditorFileRequestText.protocol(batch.getFileSha256()), lines, linesTotal, message, notice, request,
                batch.isInProgress());
    }

    private static String displayName(CreditorView c) {
        return c.tradeName() == null || c.tradeName().isBlank() ? c.legalName() : c.tradeName();
    }
}
