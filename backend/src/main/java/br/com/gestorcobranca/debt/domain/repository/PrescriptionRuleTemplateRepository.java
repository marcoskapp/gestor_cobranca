package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;

import java.util.List;

/**
 * Tabela de mercado de prazos de prescrição ({@code debt.prescription_rule_template}, dado
 * global mantido por migration, sem tenant). Ponto de partida das regras de cada assessoria,
 * sempre copiado como pendente de validação jurídica.
 */
public interface PrescriptionRuleTemplateRepository {

    List<Template> findAll();

    record Template(InstrumentType instrumentType, PrescriptionClaim claim, PrescriptionRule.Terms terms) {
    }
}
