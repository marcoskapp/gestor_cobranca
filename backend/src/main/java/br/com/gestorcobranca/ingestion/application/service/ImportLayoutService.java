package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;
import br.com.gestorcobranca.ingestion.domain.repository.ImportLayoutRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class ImportLayoutService implements ImportLayoutUseCases {

    private final ImportLayoutRepository layouts;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    ImportLayoutService(ImportLayoutRepository layouts, CreditorUseCases creditors, AccessScope accessScope,
                        AuditTrail auditTrail) {
        this.layouts = layouts;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public ImportLayoutView publish(PublishLayoutCommand command) {
        Guard.require(command.creditorId() != null, "import_layout.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(command.creditorId());
        creditors.requireActive(command.creditorId());
        ImportFormat format = parseFormat(command.format());
        List<FileLayout.Column> columns = command.columns() == null ? List.of() : command.columns().stream()
                .map(c -> new FileLayout.Column(CanonicalField.byColumn(c.field()).orElseThrow(() ->
                        new BusinessRuleException("import_layout.field_invalid", "Campo canônico desconhecido: " + c.field())),
                        c.header(), c.position()))
                .toList();
        String code = command.code() == null ? null : command.code().strip().toUpperCase(Locale.ROOT);
        int version = code == null ? 1 : layouts.maxVersion(command.creditorId(), code) + 1;
        ImportLayout layout = new ImportLayout(command.creditorId(), code, version, command.name(), format,
                delimiter(command.delimiter()), command.encoding(), command.hasHeader(), command.sheetName(), columns);
        Optional<ImportLayout> previous = layouts.findByCreditorIdAndCodeAndActiveTrue(command.creditorId(), layout.getCode());
        previous.ifPresent(p -> {
            p.deactivate();
            layouts.saveAndFlush(p);
        });
        layouts.save(layout);
        auditTrail.record(AuditEntry.of("import_layout.published", "import_layout", layout.getId())
                .creditor(layout.getCreditorId())
                .created("code", layout.getCode())
                .created("version", layout.getLayoutVersion())
                .created("format", layout.getFormat())
                .created("hasHeader", layout.isHasHeader())
                .created("columns", layout.getColumns().size())
                .meta("replacedLayoutId", previous.map(ImportLayout::getId).orElse(null)));
        return ImportViews.layout(layout);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportLayoutView get(UUID layoutId) {
        return ImportViews.layout(loadVisible(layoutId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportLayoutView> list(UUID creditorId, boolean includeInactive) {
        if (creditorId != null && !accessScope.canAccessCreditor(creditorId)) {
            return List.of();
        }
        List<UUID> allowed = accessScope.hasAllCreditors() ? null : List.copyOf(accessScope.restrictCreditors(null));
        return layouts.list(allowed, creditorId, includeInactive).stream().map(ImportViews::layout).toList();
    }

    @Override
    public ImportLayoutView deactivate(UUID layoutId) {
        ImportLayout layout = loadVisible(layoutId);
        accessScope.requireCreditor(layout.getCreditorId());
        Guard.requireState(layout.isActive(), "import_layout.already_inactive", "Layout já está inativo");
        layout.deactivate();
        layouts.save(layout);
        auditTrail.record(AuditEntry.of("import_layout.deactivated", "import_layout", layoutId)
                .creditor(layout.getCreditorId())
                .change("active", true, false));
        return ImportViews.layout(layout);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CanonicalFieldView> canonicalFields() {
        return Arrays.stream(CanonicalField.values())
                .map(f -> new CanonicalFieldView(f.column(), f.required(), f.personal()))
                .toList();
    }

    private ImportLayout loadVisible(UUID layoutId) {
        ImportLayout layout = layouts.findById(layoutId)
                .orElseThrow(() -> ResourceNotFoundException.of("import_layout", layoutId));
        accessScope.requireVisible("import_layout", layoutId, layout.getCreditorId());
        return layout;
    }

    private static ImportFormat parseFormat(String format) {
        if ("CSV".equalsIgnoreCase(format)) {
            return ImportFormat.CSV;
        }
        if ("XLSX".equalsIgnoreCase(format)) {
            return ImportFormat.XLSX;
        }
        throw new BusinessRuleException("import_layout.format_invalid", "Formato do layout deve ser CSV ou XLSX");
    }

    private static Character delimiter(String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return null;
        }
        if ("\\t".equals(delimiter) || "TAB".equalsIgnoreCase(delimiter)) {
            return '\t';
        }
        Guard.require(delimiter.length() == 1, "import_layout.delimiter_invalid", "Delimitador do CSV deve ser um caractere");
        return delimiter.charAt(0);
    }
}
