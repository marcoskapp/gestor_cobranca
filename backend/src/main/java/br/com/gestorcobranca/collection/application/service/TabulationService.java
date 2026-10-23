package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.repository.TabulationRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Catálogo de tabulações da assessoria (configuração global: alterar exige acesso a todos os credores). */
@Service
@Transactional
class TabulationService implements TabulationUseCases {

    private final TabulationRepository tabulations;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    TabulationService(TabulationRepository tabulations, AccessScope accessScope, AuditTrail auditTrail) {
        this.tabulations = tabulations;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public TabulationView create(TabulationCommand command) {
        accessScope.requireAllCreditors("tabulation");
        Guard.require(command != null, "tabulation.definition_required", "Definição obrigatória");
        Tabulation tabulation = new Tabulation(command.code(), definition(command));
        if (tabulations.findByCode(tabulation.getCode()).isPresent()) {
            throw new ConflictException("tabulation.code_taken", "Já existe tabulação com o código " + tabulation.getCode());
        }
        Tabulation saved = tabulations.save(tabulation);
        auditTrail.record(AuditEntry.of("tabulation.created", "tabulation", saved.getId())
                .created("code", saved.getCode())
                .created("name", saved.getName())
                .created("flags", flags(saved)));
        return CollectionViews.tabulation(saved);
    }

    @Override
    public TabulationView update(UUID tabulationId, TabulationCommand command) {
        accessScope.requireAllCreditors("tabulation");
        Tabulation tabulation = load(tabulationId);
        String beforeName = tabulation.getName();
        String beforeFlags = flags(tabulation);
        tabulation.define(definition(command));
        Tabulation saved = tabulations.save(tabulation);
        auditTrail.record(AuditEntry.of("tabulation.updated", "tabulation", saved.getId())
                .change("name", beforeName, saved.getName())
                .change("flags", beforeFlags, flags(saved)));
        return CollectionViews.tabulation(saved);
    }

    @Override
    public TabulationView activate(UUID tabulationId) {
        return toggle(tabulationId, true);
    }

    @Override
    public TabulationView deactivate(UUID tabulationId) {
        return toggle(tabulationId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TabulationView> list(boolean activeOnly) {
        return tabulations.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .filter(t -> !activeOnly || t.isActive())
                .map(CollectionViews::tabulation).toList();
    }

    private TabulationView toggle(UUID tabulationId, boolean active) {
        accessScope.requireAllCreditors("tabulation");
        Tabulation tabulation = load(tabulationId);
        if (tabulation.isActive() == active) {
            return CollectionViews.tabulation(tabulation);
        }
        if (active) {
            tabulation.activate();
        } else {
            tabulation.deactivate();
        }
        Tabulation saved = tabulations.save(tabulation);
        auditTrail.record(AuditEntry.of(active ? "tabulation.activated" : "tabulation.deactivated", "tabulation",
                saved.getId()).change("active", !active, active));
        return CollectionViews.tabulation(saved);
    }

    private Tabulation load(UUID id) {
        return tabulations.findById(id).orElseThrow(() -> ResourceNotFoundException.of("tabulation", id));
    }

    private static Tabulation.Definition definition(TabulationCommand c) {
        Guard.require(c != null, "tabulation.definition_required", "Definição obrigatória");
        return new Tabulation.Definition(c.name(), c.effectiveContact(), c.promise(), c.callback(), c.requiresNote(),
                c.displayOrder());
    }

    private static String flags(Tabulation t) {
        return "effectiveContact=%s;promise=%s;callback=%s;requiresNote=%s".formatted(t.isEffectiveContact(),
                t.isPromise(), t.isCallback(), t.isRequiresNote());
    }
}
