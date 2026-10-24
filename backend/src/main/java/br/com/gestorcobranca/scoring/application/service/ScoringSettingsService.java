package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases;
import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;
import br.com.gestorcobranca.scoring.domain.model.LegalBasis;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.ScoringSettingsRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Parâmetros do score e base legal; alterações auditadas (antes/depois). */
@Service
@Transactional
class ScoringSettingsService implements ScoringSettingsUseCases {

    private final SettingsProvider provider;
    private final ScoringSettingsRepository repository;
    private final ModelRegistry registry;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    ScoringSettingsService(SettingsProvider provider, ScoringSettingsRepository repository, ModelRegistry registry,
                           AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail, Clock clock) {
        this.provider = provider;
        this.repository = repository;
        this.registry = registry;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public SettingsView get() {
        return ScoringViews.settings(provider.current());
    }

    @Override
    public SettingsView update(SettingsCommand c) {
        accessScope.requireAllCreditors("scoring_settings");
        Guard.require(c.bandCutoffs() != null && c.bandCutoffs().size() == 4, "scoring.band_cutoffs_invalid",
                "Informe os 4 cortes das faixas (A, B, C, D)");
        ScoringSettings.Parameters next = new ScoringSettings.Parameters(c.outcomeDays(), c.minCases(), c.minPositives(),
                c.minPartitionPositives(), c.validationShare(), c.l2Lambda(), c.minAuc(), c.trainingWindowDays(),
                c.snapshotIntervalDays(), c.publishMinDelta(), c.psiWarning(), c.psiAlert(),
                new BandCutoffs(c.bandCutoffs().get(0), c.bandCutoffs().get(1), c.bandCutoffs().get(2),
                        c.bandCutoffs().get(3)), c.defaultPaidShare(), c.autoTrain());
        ScoringSettings s = provider.current();
        ScoringSettings.Parameters before = s.parameters();
        s.apply(next);
        ScoringSettings saved = repository.save(s);
        auditTrail.record(AuditEntry.of("scoring_settings.updated", "scoring_settings", saved.getId())
                .change("parameters", before.toString(), saved.parameters().toString()));
        return ScoringViews.settings(saved);
    }

    @Override
    public SettingsView registerLegalBasis(LegalBasisCommand c) {
        accessScope.requireAllCreditors("scoring_settings");
        LegalBasis basis = ScoringViews.parse(LegalBasis.class, c.legalBasis(), "scoring.legal_basis_invalid",
                "Base legal");
        ScoringSettings s = provider.current();
        LegalBasis previous = s.getLegalBasis();
        String previousReference = s.getLegalBasisReference();
        Instant now = Instant.now(clock);
        s.registerLegalBasis(basis, c.reference(), currentActor.actorId(), now);
        ScoringSettings saved = repository.save(s);
        registry.ensureChampion(saved);
        auditTrail.record(AuditEntry.of("scoring_settings.legal_basis_registered", "scoring_settings", saved.getId())
                .change("legalBasis", previous, basis)
                .change("legalBasisReference", previousReference, saved.getLegalBasisReference()));
        return ScoringViews.settings(saved);
    }
}
