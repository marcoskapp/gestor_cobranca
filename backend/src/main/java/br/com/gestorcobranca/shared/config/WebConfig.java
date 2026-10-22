package br.com.gestorcobranca.shared.config;

import br.com.gestorcobranca.shared.web.ActorContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final ActorContextInterceptor actorContextInterceptor;

    WebConfig(ActorContextInterceptor actorContextInterceptor) {
        this.actorContextInterceptor = actorContextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(actorContextInterceptor).addPathPatterns("/api/**");
    }
}
