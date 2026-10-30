package br.com.gestorcobranca.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serve o frontend Angular empacotado em {@code classpath:/static/}. Rotas da SPA (ex.:
 * {@code /carteiras/123}) que não correspondem a um arquivo retornam {@code index.html}, para
 * que o roteador do Angular as resolva. Caminhos de API, actuator e documentação nunca caem no
 * fallback (respondem 404 normalmente).
 * <p>
 * Cache: arquivos com hash no nome (gerados pelo build do Angular) por 1 ano; {@code index.html}
 * sem cache, para que uma nova versão seja carregada imediatamente após o deploy.
 */
@Configuration
class SpaResourceConfig implements WebMvcConfigurer {

    private static final String STATIC = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // arquivos com hash de conteúdo no nome: imutáveis
        CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
        registry.addResourceHandler("/*.js", "/*.css").addResourceLocations(STATIC).setCacheControl(immutable);
        registry.addResourceHandler("/media/**").addResourceLocations(STATIC + "media/").setCacheControl(immutable);
        // demais caminhos (index.html e rotas da SPA): sempre revalidar
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static final class SpaFallbackResolver extends PathResourceResolver {

        private static final Resource INDEX = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable() && !resourcePath.isEmpty()) {
                return requested;
            }
            if (isBackendPath(resourcePath) || looksLikeFile(resourcePath) || !INDEX.exists()) {
                return null;
            }
            return INDEX;
        }

        private static boolean isBackendPath(String path) {
            return path.startsWith("api/") || path.startsWith("public-api") || path.startsWith("actuator") || path.startsWith("v3/api-docs")
                    || path.startsWith("swagger-ui");
        }

        /** Arquivo inexistente com extensão (ex.: .js antigo) deve retornar 404, não a SPA. */
        private static boolean looksLikeFile(String path) {
            int slash = path.lastIndexOf('/');
            return path.substring(slash + 1).contains(".");
        }
    }
}
