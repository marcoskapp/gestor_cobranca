package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.BusinessUnitUseCases;
import br.com.gestorcobranca.organization.domain.model.BusinessUnit;
import br.com.gestorcobranca.organization.domain.repository.BusinessUnitRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class BusinessUnitService implements BusinessUnitUseCases {

    private final BusinessUnitRepository units;
    private final AuditTrail auditTrail;

    BusinessUnitService(BusinessUnitRepository units, AuditTrail auditTrail) {
        this.units = units;
        this.auditTrail = auditTrail;
    }

    @Override
    public BusinessUnitView create(String code, String name) {
        if (code != null && units.existsByCode(code.trim().toUpperCase())) {
            throw new ConflictException("business_unit.code_taken", "Código de unidade já utilizado");
        }
        BusinessUnit unit = units.save(new BusinessUnit(code, name));
        auditTrail.record(AuditEntry.of("business_unit.created", "business_unit", unit.getId())
                .created("code", unit.getCode()).created("name", unit.getName()));
        return view(unit);
    }

    @Override
    public BusinessUnitView update(UUID id, String name, boolean active) {
        BusinessUnit unit = load(id);
        AuditEntry audit = AuditEntry.of("business_unit.updated", "business_unit", id)
                .change("name", unit.getName(), name)
                .change("active", unit.isActive(), active);
        unit.rename(name);
        if (active) {
            unit.activate();
        } else {
            unit.deactivate();
        }
        units.save(unit);
        auditTrail.record(audit);
        return view(unit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BusinessUnitView> list() {
        return units.findAll().stream()
                .sorted(Comparator.comparing(BusinessUnit::getName))
                .map(BusinessUnitService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessUnitView get(UUID id) {
        return view(load(id));
    }

    private BusinessUnit load(UUID id) {
        return units.findById(id).orElseThrow(() -> ResourceNotFoundException.of("business_unit", id));
    }

    private static BusinessUnitView view(BusinessUnit unit) {
        return new BusinessUnitView(unit.getId(), unit.getCode(), unit.getName(), unit.isActive());
    }
}
