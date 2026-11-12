package br.com.gestorcobranca.costing.domain.model;

/**
 * Base dos custos de canal no painel e na exportação (desenho §3.5 e §13): {@link #ESTIMATED} = uso × tarifa (padrão,
 * compatível com a fase 1); {@link #RECONCILED} = estimativa + ajuste pela fatura informada (telefonia, mensagens e IA).
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum MarginBasis {
    ESTIMATED,
    RECONCILED
}
