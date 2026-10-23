package br.com.gestorcobranca.debt.application.service;

/** Origem de uma alteração, registrada na auditoria, nos eventos de negócio e nas métricas. */
final class ChangeSource {

    static final String MANUAL = "MANUAL";
    static final String IMPORT = "IMPORT";
    static final String LEGAL = "LEGAL";

    private ChangeSource() {
    }
}
