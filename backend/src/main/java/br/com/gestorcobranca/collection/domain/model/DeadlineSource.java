package br.com.gestorcobranca.collection.domain.model;

/**
 * De onde veio o prazo da contestação (hierarquia, do mais específico ao mais geral): informado na abertura,
 * regra do credor para o tipo, regra do credor (todos os tipos), regra da assessoria para o tipo, padrão da assessoria.
 */
public enum DeadlineSource {
    INFORMED,
    CREDITOR_TYPE,
    CREDITOR,
    TYPE,
    TENANT_DEFAULT
}
