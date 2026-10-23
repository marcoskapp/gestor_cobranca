package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.MonetaryIndexUseCases;
import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndex;
import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndexRate;
import br.com.gestorcobranca.negotiation.domain.repository.MonetaryIndexRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class MonetaryIndexService implements MonetaryIndexUseCases {

    private static final String RESOURCE = "monetary_index";

    private final MonetaryIndexRepository indexes;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    MonetaryIndexService(MonetaryIndexRepository indexes, AccessScope accessScope, AuditTrail auditTrail) {
        this.indexes = indexes;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public IndexView create(IndexCommand command) {
        requireManage();
        String code = normalize(command.code());
        if (code != null && indexes.existsByCode(code)) {
            throw new ConflictException("index.code_taken", "Já existe índice com este código");
        }
        MonetaryIndex index = indexes.save(new MonetaryIndex(code, command.name(), command.source()));
        auditTrail.record(AuditEntry.of("monetary_index.created", RESOURCE, index.getId())
                .created("code", index.getCode()).created("name", index.getName()).created("source", index.getSource()));
        return view(index, List.of());
    }

    @Override
    public IndexView update(String code, IndexCommand command) {
        requireManage();
        MonetaryIndex index = load(code);
        AuditEntry audit = AuditEntry.of("monetary_index.updated", RESOURCE, index.getId())
                .change("name", index.getName(), command.name() == null ? null : command.name().trim())
                .change("source", index.getSource(), command.source());
        index.describe(command.name(), command.source());
        indexes.save(index);
        auditTrail.record(audit);
        return view(index, indexes.findRates(index.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IndexView> list() {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        return indexes.findAllIndexes().stream().map(i -> {
            List<MonetaryIndexRate> rates = indexes.findRates(i.getId());
            return new IndexView(i.getId(), i.getCode(), i.getName(), i.getSource(),
                    rates.isEmpty() ? null : rates.getLast().getCompetence(), List.of());
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IndexView get(String code) {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        MonetaryIndex index = load(code);
        return view(index, indexes.findRates(index.getId()));
    }

    @Override
    public IndexView putRates(String code, List<RateInput> rates) {
        requireManage();
        Guard.require(rates != null && !rates.isEmpty() && rates.size() <= 600, "index.rates_invalid",
                "Informe de 1 a 600 competências");
        MonetaryIndex index = load(code);
        Set<YearMonth> seen = new HashSet<>();
        for (RateInput r : rates) {
            Guard.require(r != null && r.competence() != null, "index.rate_invalid", "Competência obrigatória");
            Guard.require(seen.add(r.competence()), "index.rate_duplicated", "Competência repetida: " + r.competence());
        }
        Map<YearMonth, MonetaryIndexRate> existing = indexes.findRates(index.getId()).stream()
                .collect(Collectors.toMap(MonetaryIndexRate::getCompetence, Function.identity()));
        AuditEntry audit = AuditEntry.of("monetary_index.rates_changed", RESOURCE, index.getId()).meta("code", index.getCode());
        int changes = 0;
        for (RateInput r : rates) {
            MonetaryIndexRate current = existing.get(r.competence());
            if (current == null) {
                MonetaryIndexRate created = indexes.saveRate(new MonetaryIndexRate(index.getId(), r.competence(), r.ratePercent()));
                audit.created(r.competence().toString(), created.getRatePercent());
                changes++;
            } else {
                var before = current.getRatePercent();
                if (current.changeRate(r.ratePercent())) {
                    indexes.saveRate(current);
                    audit.change(r.competence().toString(), before, current.getRatePercent());
                    changes++;
                }
            }
        }
        if (changes > 0) {
            auditTrail.record(audit.meta("changes", changes));
        }
        return view(index, indexes.findRates(index.getId()));
    }

    private void requireManage() {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
    }

    private MonetaryIndex load(String code) {
        String normalized = normalize(code);
        return indexes.findByCode(normalized == null ? "" : normalized)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, code));
    }

    private static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    private static IndexView view(MonetaryIndex index, List<MonetaryIndexRate> rates) {
        List<RateView> views = rates.stream().map(r -> new RateView(r.getCompetence(), r.getRatePercent())).toList();
        return new IndexView(index.getId(), index.getCode(), index.getName(), index.getSource(),
                views.isEmpty() ? null : views.getLast().competence(), views);
    }
}
