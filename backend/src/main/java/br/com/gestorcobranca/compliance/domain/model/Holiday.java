package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Feriado cadastrado pela assessoria. As datas não ficam fixas em código: cada assessoria
 * mantém o próprio calendário (nacionais, estaduais ou municipais da sua operação).
 */
@Entity
@Table(name = "holiday", schema = "compliance")
public class Holiday extends TenantScopedEntity {

    @Column(name = "holiday_date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    protected Holiday() {
    }

    public Holiday(LocalDate date, String name) {
        Guard.require(date != null, "holiday.date_required", "Data do feriado obrigatória");
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "holiday.name_invalid",
                "Nome do feriado obrigatório (até 120 caracteres)");
        this.date = date;
        this.name = name.trim();
    }

    public LocalDate getDate() {
        return date;
    }

    public String getName() {
        return name;
    }
}
