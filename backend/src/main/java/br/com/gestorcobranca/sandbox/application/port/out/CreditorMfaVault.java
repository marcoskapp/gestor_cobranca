package br.com.gestorcobranca.sandbox.application.port.out;

import java.util.Optional;

/**
 * Cofre do sandbox com o segredo do autenticador (TOTP) de cada pessoa do credor de demonstração (opção D da fatia 8).
 * O segredo é o aleatório que o próprio fluxo de ativação devolveu à carga ({@code activation/start}), como o QR que a
 * pessoa leria no celular — nunca fixo, nunca derivado de algo versionado e nunca obtido por outro caminho. Fica num
 * arquivo do volume exclusivo do sandbox, com permissão 600, lido só pelos scripts locais
 * ({@code credor-codigo.sh}, {@code audit.sh}, {@code verify.sh}); nunca no log, em evento ou em métrica.
 */
public interface CreditorMfaVault {

    /** Acrescenta ou substitui (mesma assessoria e e-mail) a entrada. */
    void store(Entry entry);

    /** Segredo Base32 guardado para a pessoa, se houver. */
    Optional<String> secret(String tenant, String email);

    /**
     * @param tenant assessoria (slug)
     * @param email  e-mail da pessoa do credor (fictício)
     * @param role   perfil no portal do credor
     * @param secret segredo Base32 do autenticador
     */
    record Entry(String tenant, String email, String role, String secret) {

        @Override
        public String toString() {
            return "Entry[" + tenant + "/" + role + ", secret=***]";
        }
    }
}
