package br.com.gestorcobranca.sandbox.infrastructure.health;

import br.com.gestorcobranca.sandbox.application.service.SeedStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Situação da carga do sandbox no grupo de prontidão ({@code sandboxSeed}): fora do ar (OUT_OF_SERVICE) enquanto a
 * carga roda e DOWN se ela falhar. Não expõe dados pessoais: só etapa, horários, avisos e o motivo da falha.
 */
@Component("sandboxSeedHealthIndicator")
@Profile("sandbox")
class SandboxSeedHealthIndicator implements HealthIndicator {

    private final SeedStatus status;

    SandboxSeedHealthIndicator(SeedStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        Health.Builder builder = switch (status.state()) {
            case COMPLETED, DISABLED -> Health.up();
            case FAILED -> Health.down();
            case PENDING, RUNNING -> Health.outOfService();
        };
        builder.withDetail("state", status.state().name()).withDetail("phase", status.phase());
        if (status.startedAt() != null) {
            builder.withDetail("startedAt", status.startedAt().toString());
        }
        if (status.finishedAt() != null) {
            builder.withDetail("finishedAt", status.finishedAt().toString());
        }
        if (status.failure() != null) {
            builder.withDetail("failure", status.failure());
        }
        if (!status.warnings().isEmpty()) {
            builder.withDetail("warnings", status.warnings());
        }
        return builder.build();
    }
}
