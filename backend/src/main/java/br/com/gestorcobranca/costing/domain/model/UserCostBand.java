package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Faixa de custo de uma pessoa a partir de uma data. Aponta para o <b>nome</b> da faixa: o valor-hora do dia vem da
 * vigência da faixa ({@link CostBand}) naquele dia.
 */
@Entity
@Table(name = "user_cost_band", schema = "costing")
public class UserCostBand extends Dated {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "band_name", nullable = false, length = CostBand.NAME_MAX)
    private String bandName;

    protected UserCostBand() {
    }

    public UserCostBand(UUID userId, String bandName, LocalDate validFrom) {
        super(validFrom);
        this.userId = Guard.requireNonNull(userId, "costing.user_required", "Informe o usuário");
        this.bandName = CostBand.normalizeName(bandName);
    }

    /** Troca a faixa de uma vigência que ainda não começou (correção antes de valer). */
    public void changeBand(String bandName, LocalDate today) {
        Guard.requireState(getValidFrom().isAfter(today), "costing.user_band_already_started",
                "A faixa já está valendo: informe uma nova data de início");
        this.bandName = CostBand.normalizeName(bandName);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getBandName() {
        return bandName;
    }
}
