package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.domain.repository.TenantRepository;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Fornece aos jobs a lista de assessorias ativas (a tabela de tenants não tem RLS). */
@Service
class ActiveTenantDirectory implements TenantDirectory {

    private final TenantRepository tenants;

    ActiveTenantDirectory(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> activeTenantIds() {
        return tenants.findActiveIds();
    }
}
