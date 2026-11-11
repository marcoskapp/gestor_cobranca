package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectResponseDraftUseCases;
import br.com.gestorcobranca.compliance.domain.model.DataProcessor;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.LegalBasis;
import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.compliance.domain.repository.DataProcessorRepository;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Draft;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.ProcessorInfo;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Recipient;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rascunho de resposta ao titular (art. 18, I e VII): coleta os fatos das fontes de compartilhamento, agrega por
 * destinatário, acrescenta a base legal vigente dos credores e o cadastro dos operadores, e monta o texto pelo
 * {@link ResponseDraftComposer}. Nada é gravado na solicitação: a resposta só existe quando o analista conclui.
 */
@Service
class DataSubjectResponseDraftService implements DataSubjectResponseDraftUseCases {

    private static final String RESOURCE = "data_subject_request";

    private final DataSubjectRequestRepository requests;
    private final LegalBasisRecordRepository legalBases;
    private final DataProcessorRepository processors;
    private final DataSharingCollector collector;
    private final DebtorUseCases debtors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;

    DataSubjectResponseDraftService(DataSubjectRequestRepository requests, LegalBasisRecordRepository legalBases,
                                    DataProcessorRepository processors, DataSharingCollector collector,
                                    DebtorUseCases debtors, AccessScope accessScope, CurrentActor currentActor,
                                    AuditTrail auditTrail, AccessLogger accessLogger,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = requests;
        this.legalBases = legalBases;
        this.processors = processors;
        this.collector = collector;
        this.debtors = debtors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactionManager);
    }

    /**
     * Sem transação externa: cada fonte de compartilhamento abre a própria (somente leitura, com o ator da requisição),
     * e segurar uma conexão aqui enquanto elas rodam dobraria o uso do pool. A leitura da solicitação e a montagem com
     * a trilha (acesso a dado sensível e auditoria, antes de devolver a resposta) rodam em transações curtas próprias.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ResponseDraftView draft(UUID requestId) {
        // mesmo alcance de atuar na solicitação: o direito do titular alcança todos os seus dados na assessoria
        accessScope.requireAllCreditors(RESOURCE);
        Subject subject = readOnly.execute(s -> subject(requestId));
        List<Aggregated> collectedRecipients = List.of();
        List<String> unavailable = List.of();
        if (subject.found()) {
            DataSharingCollector.Collected collected = collector.sharedWith(new DataSharingQuery(subject.debtorId(), null));
            collectedRecipients = aggregate(collected.facts());
            unavailable = collected.unavailable();
        }
        List<Aggregated> fromSources = collectedRecipients;
        List<String> unavailableSources = unavailable;
        return readWrite.execute(s -> compose(subject, fromSources, unavailableSources));
    }

    /** O que a montagem precisa da solicitação, lido e validado numa transação curta. */
    private record Subject(UUID requestId, RequestType type, String requesterName, Instant receivedAt, UUID debtorId,
                           boolean found) {
    }

    private Subject subject(UUID requestId) {
        DataSubjectRequest request = requests.findById(requestId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, requestId));
        if (!ResponseDraftComposer.supports(request.getType())) {
            throw new BusinessRuleException("dsr.draft_unavailable",
                    "Resposta sugerida disponível só para confirmação de tratamento e informação sobre compartilhamento");
        }
        if (request.getStatus().isFinal()) {
            throw new ConflictException("dsr.draft_closed", "Solicitação encerrada: a resposta já foi registrada");
        }
        if (request.getIdentityVerifiedAt() == null) {
            throw new ConflictException("dsr.identity_not_verified",
                    "Confirme a identidade do titular antes de montar a resposta");
        }
        UUID debtorId = request.getDebtorId();
        boolean found = debtorId != null && !"ANONYMIZED".equals(debtors.get(debtorId).status());
        return new Subject(request.getId(), request.getType(), request.getRequesterName(), request.getReceivedAt(),
                debtorId, found);
    }

    private ResponseDraftView compose(Subject subject, List<Aggregated> fromSources, List<String> unavailable) {
        UUID requestId = subject.requestId();
        UUID debtorId = subject.debtorId();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        List<Aggregated> aggregated = subject.found() ? withManualProcessors(fromSources, today) : fromSources;
        Map<UUID, List<LegalBasis>> bases = effectiveBases(aggregated, today);
        Map<String, DataProcessor> registry = processors.findByProviderKeyIn(aggregated.stream()
                        .filter(a -> a.type == DataRecipientType.PROVIDER).map(a -> a.key).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DataProcessor::getProviderKey, Function.identity()));

        List<Recipient> recipients = aggregated.stream().map(a -> new Recipient(a.type, a.key, a.name, a.purposes,
                a.type == DataRecipientType.CREDITOR ? bases.getOrDefault(a.recipientId, List.of()) : List.of(),
                processorInfo(registry.get(a.key), today))).toList();
        Draft draft = ResponseDraftComposer.compose(new ResponseDraftComposer.Input(subject.type(),
                subject.requesterName(), LocalDate.ofInstant(subject.receivedAt(), TimeConfig.BUSINESS_ZONE),
                subject.found(), recipients, unavailable));
        String text = draft.text();
        String sha256 = sha256(text);

        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), details(subject,
                aggregated.size())));
        auditTrail.record(AuditEntry.of("data_subject_request.response_draft_generated", RESOURCE, requestId)
                .meta("debtorId", debtorId)
                .meta("recipients", aggregated.size())
                .meta("alerts", draft.alerts().stream().map(ResponseDraftComposer.Alert::code).distinct().sorted()
                        .collect(Collectors.joining(",")))
                .meta("unavailable", unavailable.isEmpty() ? null : String.join(", ", unavailable))
                .meta("textSha256", sha256));

        Map<String, UUID> idsByKey = new LinkedHashMap<>();
        aggregated.forEach(a -> idsByKey.put(a.key, a.recipientId));
        return new ResponseDraftView(requestId, subject.type().name(), now, draft.processing(),
                draft.categories().stream().map(ResponseDraftComposer.DataCategory::label).toList(),
                aggregated.stream().map(a -> recipientView(a, bases, registry.get(a.key), today)).toList(),
                draft.alerts().stream().map(x -> new AlertView(x.code(), x.severity().name(), x.message(),
                        x.recipientKey(), x.recipientKey() == null ? null : idsByKey.get(x.recipientKey()))).toList(),
                unavailable,
                draft.paragraphs().stream().map(p -> new ParagraphView(p.text(), p.recipientKeys())).toList(),
                text, sha256, ResponseDraftComposer.TEXT_MAX);
    }

    /**
     * Operadores cadastrados à mão ({@code custom.<nome>}, ex.: hospedagem): nenhuma fonte registra o uso deles, mas
     * tratam os dados de todos os titulares em nome da assessoria. Sem eles, a frase "não compartilhamos com outras
     * empresas além das listadas" seria falsa. Entram os ativos e vigentes hoje, com a finalidade do cadastro.
     */
    private List<Aggregated> withManualProcessors(List<Aggregated> fromSources, LocalDate today) {
        List<Aggregated> manual = processors.findByProviderKeyStartingWithAndActiveTrue(DataProcessor.CUSTOM_KEY_PREFIX)
                .stream().filter(p -> p.isCurrentOn(today))
                .filter(p -> fromSources.stream().noneMatch(a -> a.key.equals(p.getProviderKey())))
                .sorted(Comparator.comparing(DataProcessor::getName))
                .map(Aggregated::manual).toList();
        if (manual.isEmpty()) {
            return fromSources;
        }
        List<Aggregated> all = new ArrayList<>(fromSources);
        all.addAll(manual);
        all.sort(Comparator.comparingInt((Aggregated a) -> a.type.ordinal()));
        return List.copyOf(all);
    }

    private static Map<String, String> details(Subject subject, int recipients) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", "debtor");
        if (subject.debtorId() != null) {
            details.put("resourceId", subject.debtorId().toString());
        }
        details.put("view", "data_subject_response_draft");
        details.put("requestId", subject.requestId().toString());
        details.put("requestType", subject.type().name());
        details.put("recipients", Integer.toString(recipients));
        return details;
    }

    /** Fatos da mesma chave (ex.: duas contas do mesmo provedor) viram um destinatário. */
    private static List<Aggregated> aggregate(List<DataSharingFact> facts) {
        Map<String, Aggregated> byKey = new LinkedHashMap<>();
        for (DataSharingFact f : facts) {
            byKey.computeIfAbsent(f.recipientKey(), k -> new Aggregated(f)).add(f);
        }
        return byKey.values().stream()
                .sorted(Comparator.comparingInt((Aggregated a) -> a.type.ordinal()))
                .toList();
    }

    private Map<UUID, List<LegalBasis>> effectiveBases(List<Aggregated> recipients, LocalDate today) {
        Set<UUID> creditorIds = recipients.stream().filter(a -> a.type == DataRecipientType.CREDITOR)
                .map(a -> a.recipientId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (creditorIds.isEmpty()) {
            return Map.of();
        }
        return legalBases.findByCreditorIdIn(creditorIds).stream()
                .filter(r -> r.isEffectiveOn(today))
                .collect(Collectors.groupingBy(LegalBasisRecord::getCreditorId,
                        Collectors.mapping(LegalBasisRecord::getLegalBasis,
                                Collectors.collectingAndThen(Collectors.toCollection(() -> EnumSet.noneOf(LegalBasis.class)),
                                        List::copyOf))));
    }

    private static ProcessorInfo processorInfo(DataProcessor p, LocalDate today) {
        return p == null ? null : new ProcessorInfo(p.getName(), p.getRole(), p.getPurpose(), p.getCountryCode(),
                p.getTransferSafeguard(), p.isCurrentOn(today));
    }

    private static RecipientView recipientView(Aggregated a, Map<UUID, List<LegalBasis>> bases, DataProcessor p,
                                               LocalDate today) {
        ProcessorSummary processor = p == null ? null : new ProcessorSummary(p.getId(), p.getName(), p.getRole().label(),
                p.getPurpose(), p.getCountryCode(), ResponseDraftComposer.countryName(p.getCountryCode()),
                p.isInternationalTransfer(),
                p.getTransferSafeguard() == null ? null
                        : p.getTransferSafeguard().label() + " (" + p.getTransferSafeguard().article() + ")",
                p.getContractReference(), p.isCurrentOn(today));
        List<String> legal = a.type == DataRecipientType.CREDITOR
                ? bases.getOrDefault(a.recipientId, List.of()).stream().map(ResponseDraftComposer::legalBasisLabel).toList()
                : List.of();
        return new RecipientView(a.type.name(), a.key, a.recipientId, p != null ? p.getName() : a.name,
                a.purposes.isEmpty() && p != null ? List.of(p.getPurpose())
                        : a.purposes.stream().sorted().map(ResponseDraftComposer::purposeLabel).toList(), a.firstAt, a.lastAt,
                a.count, String.join(" · ", a.countLabels), a.links.stream().map(DataSubjectResponseDraftService::link).toList(),
                legal, processor);
    }

    private static LinkView link(DataSharingLink l) {
        return new LinkView(l.route(), l.queryParams(),
                l.anyPermission().stream().map(Permission::name).collect(Collectors.toCollection(java.util.TreeSet::new)),
                l.allPermissions().stream().map(Permission::name).collect(Collectors.toCollection(java.util.TreeSet::new)),
                l.label());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Destinatário com os fatos da mesma chave somados. */
    private static final class Aggregated {
        final DataRecipientType type;
        final String key;
        final UUID recipientId;
        final String name;
        final Set<DataSharingPurpose> purposes = EnumSet.noneOf(DataSharingPurpose.class);
        final List<String> countLabels = new ArrayList<>();
        final List<DataSharingLink> links = new ArrayList<>();
        Instant firstAt;
        Instant lastAt;
        long count;

        Aggregated(DataSharingFact first) {
            this(first.recipientType(), first.recipientKey(), first.recipientId(), first.recipientName());
        }

        private Aggregated(DataRecipientType type, String key, UUID recipientId, String name) {
            this.type = type;
            this.key = key;
            this.recipientId = recipientId;
            this.name = name;
        }

        /** Operador manual: sem fato registrado (sem período nem contagem); a finalidade vem do cadastro. */
        static Aggregated manual(DataProcessor p) {
            return new Aggregated(DataRecipientType.PROVIDER, p.getProviderKey(), null, p.getName());
        }

        Aggregated add(DataSharingFact f) {
            purposes.add(f.purpose());
            if (f.countLabel() != null) {
                countLabels.add(f.countLabel());
            }
            if (f.link() != null && !links.contains(f.link())) {
                links.add(f.link());
            }
            firstAt = min(firstAt, f.firstAt());
            lastAt = max(lastAt, f.lastAt());
            count += f.count();
            return this;
        }

        private static Instant min(Instant a, Instant b) {
            return a == null ? b : b == null ? a : (a.isBefore(b) ? a : b);
        }

        private static Instant max(Instant a, Instant b) {
            return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
        }
    }
}
