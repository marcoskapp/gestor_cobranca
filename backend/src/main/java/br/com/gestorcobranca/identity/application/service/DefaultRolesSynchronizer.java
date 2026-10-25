package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mantém os perfis padrão ({@link DefaultRoles}) de todas as assessorias alinhados à versão do
 * sistema em execução:
 * <ul>
 *   <li>cria os perfis padrão que a assessoria ainda não tem (ex.: perfis novos de uma versão);</li>
 *   <li>acrescenta aos perfis de sistema as permissões padrão ainda não aplicadas (ex.: permissões
 *       de módulos novos), cada uma uma única vez — o que o administrador retirar depois não volta;</li>
 *   <li>nunca remove permissões nem altera perfis criados pela assessoria.</li>
 * </ul>
 * Roda logo após a subida e periodicamente, com ShedLock (uma instância por vez). O tenant da
 * plataforma (perfil {@code PLATFORM_ADMIN}) não recebe perfis de assessoria.
 */
@Component
public class DefaultRolesSynchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRolesSynchronizer.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final RoleRepository roles;
    private final AuditTrail auditTrail;
    private final MeterRegistry meterRegistry;

    DefaultRolesSynchronizer(TenantDirectory tenantDirectory, TenantExecution tenantExecution, RoleRepository roles,
                             AuditTrail auditTrail, MeterRegistry meterRegistry) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.roles = roles;
        this.auditTrail = auditTrail;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(initialDelayString = "${app.identity.default-roles-sync.initial-delay:PT30S}",
            fixedDelayString = "${app.identity.default-roles-sync.interval:PT6H}")
    @SchedulerLock(name = "identity.default-roles-sync", lockAtMostFor = "PT30M")
    void scheduledSync() {
        syncAll();
    }

    /** Sincroniza todas as assessorias ativas; falha em uma não interrompe as demais. */
    public void syncAll() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                syncTenant(tenantId);
            } catch (RuntimeException e) {
                meterRegistry.counter("identity.default_roles.sync_failures").increment();
                LOG.error("Falha ao sincronizar os perfis padrão do tenant {}", tenantId, e);
            }
        }
    }

    /** @return resultado da sincronização da assessoria (vazio para o tenant da plataforma) */
    public SyncResult syncTenant(UUID tenantId) {
        return tenantExecution.callInTenant(tenantId, "identity.default_roles_sync", () -> {
            Map<String, Role> existing = roles.findAll().stream()
                    .collect(Collectors.toMap(Role::getCode, Function.identity()));
            if (existing.containsKey(DefaultRoles.PLATFORM_ADMIN)) {
                return SyncResult.EMPTY;
            }
            List<String> created = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            for (DefaultRoles.Definition definition : DefaultRoles.forTenant()) {
                Optional<Role> current = Optional.ofNullable(existing.get(definition.code()));
                if (current.isEmpty()) {
                    Role role = roles.save(new Role(definition.code(), definition.name(), definition.description(),
                            definition.permissions(), true));
                    auditTrail.record(AuditEntry.of("role.default_created", "role", role.getId())
                            .created("code", role.getCode()).created("permissions", names(definition.permissions())));
                    created.add(role.getCode());
                    continue;
                }
                Role role = current.get();
                if (!role.isSystemRole()) {
                    continue;
                }
                Set<Permission> added = role.applyDefaultPermissions(definition.permissions());
                roles.save(role);
                if (!added.isEmpty()) {
                    auditTrail.record(AuditEntry.of("role.default_permissions_added", "role", role.getId())
                            .created("permissions", names(added)));
                    updated.add(role.getCode());
                    meterRegistry.counter("identity.default_roles.permissions_added").increment(added.size());
                }
            }
            if (!created.isEmpty() || !updated.isEmpty()) {
                LOG.info("Perfis padrão sincronizados no tenant {}: criados={} atualizados={}", tenantId, created, updated);
            }
            meterRegistry.counter("identity.default_roles.roles_created").increment(created.size());
            return new SyncResult(List.copyOf(created), List.copyOf(updated));
        });
    }

    private static String names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public record SyncResult(List<String> createdRoles, List<String> updatedRoles) {
        static final SyncResult EMPTY = new SyncResult(List.of(), List.of());
    }
}
