package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.organization.domain.model.Tenant;
import br.com.gestorcobranca.organization.domain.repository.TenantRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class TenantAdministrationService implements TenantAdministration {

    private final TenantRepository tenants;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    TenantAdministrationService(TenantRepository tenants, CurrentActor currentActor, AccessScope accessScope,
                                AuditTrail auditTrail) {
        this.tenants = tenants;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public TenantView createTenant(CreateTenantCommand command) {
        accessScope.requirePermission(Permission.TENANT_MANAGE);
        String slug = command.slug() == null ? null : command.slug().trim().toLowerCase();
        if (slug != null && tenants.existsBySlug(slug)) {
            throw new ConflictException("tenant.slug_taken", "Identificador de assessoria já utilizado");
        }
        Tenant tenant = tenants.save(new Tenant(slug, command.legalName(), command.tradeName(), command.cnpj(), false));
        auditTrail.record(AuditEntry.of("tenant.created", "tenant", tenant.getId())
                .created("slug", tenant.getSlug())
                .created("legalName", tenant.getLegalName())
                .created("cnpj", tenant.getCnpj()));
        return view(tenant);
    }

    @Override
    public PlatformTenant ensurePlatformTenant(String cnpj) {
        Optional<Tenant> existing = tenants.findPlatform();
        if (existing.isPresent()) {
            return new PlatformTenant(view(existing.get()), false);
        }
        Tenant platform = tenants.save(new Tenant("plataforma", "Plataforma Gestor de Cobrança", "Plataforma", cnpj, true));
        return new PlatformTenant(view(platform), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantView> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return tenants.findBySlug(slug.trim().toLowerCase()).map(TenantAdministrationService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantView> findById(UUID tenantId) {
        return tenants.findById(tenantId).map(TenantAdministrationService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantView currentTenant() {
        return view(loadCurrent());
    }

    @Override
    public TenantView updateCurrentTenant(UpdateTenantSettingsCommand command) {
        Tenant tenant = loadCurrent();
        AuditEntry audit = AuditEntry.of("tenant.settings_updated", "tenant", tenant.getId())
                .change("legalName", tenant.getLegalName(), command.legalName())
                .change("tradeName", tenant.getTradeName(), command.tradeName())
                .change("contactWindowStart", tenant.getContactWindowStart(), command.contactWindowStart())
                .change("contactWindowEnd", tenant.getContactWindowEnd(), command.contactWindowEnd())
                .change("contactOnSaturday", tenant.isContactOnSaturday(), command.contactOnSaturday())
                .change("contactOnSunday", tenant.isContactOnSunday(), command.contactOnSunday());
        tenant.rename(command.legalName(), command.tradeName());
        tenant.defineContactWindow(command.contactWindowStart(), command.contactWindowEnd(),
                command.contactOnSaturday(), command.contactOnSunday());
        tenants.save(tenant);
        auditTrail.record(audit);
        return view(tenant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantView> listTenants() {
        accessScope.requirePermission(Permission.TENANT_MANAGE);
        return tenants.findAll().stream().map(TenantAdministrationService::view).toList();
    }

    @Override
    public TenantView changeTenantStatus(UUID tenantId, boolean active, String reason) {
        accessScope.requirePermission(Permission.TENANT_MANAGE);
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ResourceNotFoundException.of("tenant", tenantId));
        String previous = tenant.getStatus().name();
        if (active) {
            tenant.reactivate();
        } else {
            tenant.suspend();
        }
        tenants.save(tenant);
        auditTrail.record(AuditEntry.of("tenant.status_changed", "tenant", tenant.getId())
                .change("status", previous, tenant.getStatus().name())
                .reason(reason)
                .meta("targetTenantId", tenantId));
        return view(tenant);
    }

    private Tenant loadCurrent() {
        UUID tenantId = currentActor.tenantId();
        return tenants.findById(tenantId).orElseThrow(() -> ResourceNotFoundException.of("tenant", tenantId));
    }

    static TenantView view(Tenant t) {
        return new TenantView(t.getId(), t.getSlug(), t.getLegalName(), t.getTradeName(), t.getCnpj(), t.getStatus().name(),
                t.isPlatform(), t.getContactWindowStart(), t.getContactWindowEnd(), t.isContactOnSaturday(),
                t.isContactOnSunday());
    }
}
