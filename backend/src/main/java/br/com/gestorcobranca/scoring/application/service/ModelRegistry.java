package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.scoring.domain.model.LogisticDefinition;
import br.com.gestorcobranca.scoring.domain.model.ModelKind;
import br.com.gestorcobranca.scoring.domain.model.ModelStatus;
import br.com.gestorcobranca.scoring.domain.model.ScorecardDefinition;
import br.com.gestorcobranca.scoring.domain.model.ScoringFunction;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versões de modelo em uso: campeão (semeado com o scorecard padrão na primeira escoragem da
 * assessoria), desafiante e a função de escoragem de cada versão (definição imutável, decodificada
 * uma vez por instância).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ModelRegistry {

    private final ScoringModelRepository models;
    private final ScoringLocks locks;
    private final JsonCodec json;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final Map<UUID, ScoringFunction> functions = new ConcurrentHashMap<>();

    ModelRegistry(ScoringModelRepository models, ScoringLocks locks, JsonCodec json, AuditTrail auditTrail, Clock clock) {
        this.models = models;
        this.locks = locks;
        this.json = json;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /** Campeão vigente; sem nenhum, semeia o scorecard padrão (v1, sistema) como campeão. */
    ScoringModel ensureChampion(ScoringSettings settings) {
        Optional<ScoringModel> champion = models.findFirstByStatus(ModelStatus.CHAMPION);
        if (champion.isPresent()) {
            return champion.get();
        }
        locks.lockTenant("models");
        return models.findFirstByStatus(ModelStatus.CHAMPION).orElseGet(() -> {
            Instant now = Instant.now(clock);
            ScoringModel seeded = models.save(new ScoringModel(nextVersion(), ModelKind.SCORECARD, ModelStatus.CHAMPION,
                    "Scorecard padrão (pesos de especialista)", settings.getOutcomeDays(),
                    json.toJson(ScorecardDefinition.standard()), settings.bands(), settings.getDefaultPaidShare(), null,
                    null, null, null, null, now));
            auditTrail.record(AuditEntry.of("scoring_model.seeded", "scoring_model", seeded.getId())
                    .created("modelVersion", seeded.getModelVersion()).created("kind", seeded.getKind())
                    .created("status", seeded.getStatus()).created("outcomeDays", seeded.getOutcomeDays()));
            return seeded;
        });
    }

    Optional<ScoringModel> champion() {
        return models.findFirstByStatus(ModelStatus.CHAMPION);
    }

    Optional<ScoringModel> challenger() {
        return models.findFirstByStatus(ModelStatus.CHALLENGER);
    }

    Optional<ScoringModel> find(UUID id) {
        return id == null ? Optional.empty() : models.findById(id);
    }

    /** Próximo número de versão (chamar sob {@code lockTenant("models")}). */
    int nextVersion() {
        return models.maxModelVersion() + 1;
    }

    void lockModels() {
        locks.lockTenant("models");
    }

    ScoringFunction function(ScoringModel model) {
        return functions.computeIfAbsent(model.getId(), id -> decode(model.getKind(), model.getDefinition()));
    }

    ScoringFunction decode(ModelKind kind, String definition) {
        return switch (kind) {
            case SCORECARD -> json.fromJson(definition, ScorecardDefinition.class);
            case LOGISTIC_REGRESSION -> json.fromJson(definition, LogisticDefinition.class);
        };
    }
}
