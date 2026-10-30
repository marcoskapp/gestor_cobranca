package br.com.gestorcobranca.sandbox.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Parâmetros do sandbox de demonstração ({@code app.sandbox.*}, definidos em {@code application-sandbox.yml}).
 *
 * @param seed carga de dados fictícios
 */
@ConfigurationProperties(prefix = "app.sandbox")
public record SandboxProperties(Seed seed) {

    public SandboxProperties {
        seed = seed == null ? new Seed(false, null, null, null, null, null) : seed;
    }

    /**
     * @param enabled            liga a carga (exige também o perfil {@code sandbox})
     * @param demoPassword       senha de demonstração de todos os usuários (passa pela política de senha normal)
     * @param mailpitApiUrl      API do Mailpit, onde a carga lê os links de ativação enviados por e-mail
     * @param platformAdminEmail administrador da plataforma criado pelo bootstrap ({@code APP_BOOTSTRAP_*})
     * @param activationTimeout  espera máxima pelo e-mail de ativação de cada usuário
     * @param stepTimeout        espera máxima por processamentos assíncronos (importação, jobs, eventos)
     */
    public record Seed(boolean enabled, String demoPassword, URI mailpitApiUrl, String platformAdminEmail,
                       Duration activationTimeout, Duration stepTimeout) {

        public Seed {
            activationTimeout = activationTimeout == null ? Duration.ofMinutes(2) : activationTimeout;
            stepTimeout = stepTimeout == null ? Duration.ofMinutes(5) : stepTimeout;
        }

        @Override
        public String toString() {
            return "Seed[enabled=%s, mailpitApiUrl=%s, platformAdminEmail=%s, demoPassword=***]"
                    .formatted(enabled, mailpitApiUrl, platformAdminEmail);
        }
    }
}
