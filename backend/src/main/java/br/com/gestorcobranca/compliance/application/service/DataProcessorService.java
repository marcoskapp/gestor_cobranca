package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases;
import br.com.gestorcobranca.compliance.domain.model.DataProcessor;
import br.com.gestorcobranca.compliance.domain.model.ProcessorRole;
import br.com.gestorcobranca.compliance.domain.model.TransferSafeguard;
import br.com.gestorcobranca.compliance.domain.repository.DataProcessorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
class DataProcessorService implements DataProcessorUseCases {

    private static final String RESOURCE = "data_processor";

    private final DataProcessorRepository processors;
    private final DataSharingCollector collector;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    DataProcessorService(DataProcessorRepository processors, DataSharingCollector collector, AccessScope accessScope,
                         AuditTrail auditTrail, PlatformTransactionManager transactionManager, Clock clock) {
        this.processors = processors;
        this.collector = collector;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessorView> list() {
        LocalDate today = today();
        return processors.findAllByOrderByNameAsc().stream().map(p -> view(p, today)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessorView get(UUID id) {
        return view(load(id), today());
    }

    @Override
    public ProcessorView register(RegisterProcessorCommand command) {
        accessScope.requireAllCreditors(RESOURCE);
        Guard.require(command.details() != null, "data_processor.details_required", "Dados do operador obrigatórios");
        String key = command.providerKey() == null ? null : command.providerKey().trim();
        DataProcessor processor = new DataProcessor(key, details(command.details()));
        if (processors.existsByProviderKey(processor.getProviderKey())) {
            throw new ConflictException("data_processor.duplicate", "Este provedor já está no cadastro de operadores");
        }
        processors.save(processor);
        AuditEntry entry = AuditEntry.of("data_processor.registered", RESOURCE, processor.getId())
                .created("providerKey", processor.getProviderKey());
        snapshot(processor).forEach(entry::created);
        auditTrail.record(entry);
        return view(processor, today());
    }

    @Override
    public ProcessorView update(UUID id, ProcessorDetails details) {
        accessScope.requireAllCreditors(RESOURCE);
        Guard.require(details != null, "data_processor.details_required", "Dados do operador obrigatórios");
        DataProcessor processor = load(id);
        Map<String, Object> before = snapshot(processor);
        processor.update(details(details));
        processors.save(processor);
        Map<String, Object> after = snapshot(processor);
        AuditEntry entry = AuditEntry.of("data_processor.updated", RESOURCE, id)
                .meta("providerKey", processor.getProviderKey());
        before.forEach((field, old) -> {
            if (!Objects.equals(old, after.get(field))) {
                entry.change(field, old, after.get(field));
            }
        });
        auditTrail.record(entry);
        return view(processor, today());
    }

    /**
     * Contas de canal, telefonia e Pix de todos os credores: exige o alcance de todos os credores (como alterar o
     * cadastro). Sem transação externa: cada fonte abre a própria (somente leitura) e segurar uma conexão aqui enquanto
     * elas rodam dobraria o uso do pool; o cadastro é lido depois, numa transação curta.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<RecipientInUseView> unregisteredRecipients() {
        accessScope.requireAllCreditors(RESOURCE);
        List<DataSharingCollector.SourcedRecipient> inUse = collector.recipientsInUse();
        Set<String> keys = inUse.stream().map(r -> r.recipient().recipientKey()).collect(Collectors.toSet());
        Set<String> registered = keys.isEmpty() ? Set.of() : readOnly.execute(s -> processors.findByProviderKeyIn(keys)
                .stream().map(DataProcessor::getProviderKey).collect(Collectors.toSet()));
        Map<String, RecipientInUseView> missing = new LinkedHashMap<>();
        for (DataSharingCollector.SourcedRecipient r : inUse) {
            String key = r.recipient().recipientKey();
            if (!registered.contains(key)) {
                missing.putIfAbsent(key, new RecipientInUseView(key, r.recipient().recipientName(),
                        r.recipient().purpose().name(), r.source()));
            }
        }
        return List.copyOf(missing.values());
    }

    private DataProcessor load(UUID id) {
        return processors.findById(id).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, id));
    }

    private static DataProcessor.Details details(ProcessorDetails d) {
        return new DataProcessor.Details(d.name(),
                ComplianceEnums.parse(ProcessorRole.class, d.role(), "data_processor.role_invalid", "Papel"),
                d.purpose(), d.dataCategories(), d.countryCode(),
                ComplianceEnums.parseOptional(TransferSafeguard.class, d.transferSafeguard(),
                        "data_processor.safeguard_invalid", "Garantia da transferência"),
                d.contractReference(), d.validFrom(), d.validUntil(), d.active());
    }

    private static Map<String, Object> snapshot(DataProcessor p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", p.getName());
        s.put("role", p.getRole());
        s.put("purpose", p.getPurpose());
        s.put("dataCategories", p.getDataCategories());
        s.put("countryCode", p.getCountryCode());
        s.put("transferSafeguard", p.getTransferSafeguard());
        s.put("contractReference", p.getContractReference());
        s.put("validFrom", p.getValidFrom());
        s.put("validUntil", p.getValidUntil());
        s.put("active", p.isActive());
        return s;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    static ProcessorView view(DataProcessor p, LocalDate today) {
        return new ProcessorView(p.getId(), p.getProviderKey(), p.getName(), p.getRole().name(), p.getPurpose(),
                p.getDataCategories(), p.getCountryCode(), p.isInternationalTransfer(),
                ComplianceEnums.name(p.getTransferSafeguard()), p.getContractReference(), p.getValidFrom(),
                p.getValidUntil(), p.isActive(), p.isCurrentOn(today), p.getUpdatedAt());
    }
}
