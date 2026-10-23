package br.com.gestorcobranca.legal.infrastructure.provider.manual;

/**
 * Base dos adaptadores manuais: o provedor "manual" representa operações feitas fora da
 * plataforma (no balcão do cartório, no portal do tribunal, no sistema do birô) e lançadas pelo
 * usuário — protocolo, datas, situação e andamentos. Não simula provedor: os casos de uso
 * consultam {@code automatic()} e nunca chamam as operações externas deste adaptador; uma
 * chamada indica erro de programação.
 */
abstract class ManualProviderSupport {

    static final String CODE = "manual";

    public String code() {
        return CODE;
    }

    public boolean automatic() {
        return false;
    }

    static IllegalStateException notAutomatic(String operation) {
        return new IllegalStateException("Adaptador manual não executa operação externa: " + operation);
    }
}
