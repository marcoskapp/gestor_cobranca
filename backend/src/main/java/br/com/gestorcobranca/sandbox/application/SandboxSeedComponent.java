package br.com.gestorcobranca.sandbox.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Componente da carga de dados fictícios: só existe com o perfil {@code sandbox} <strong>e</strong>
 * {@code app.sandbox.seed.enabled=true} (as duas condições, sempre juntas, em todos os componentes da carga).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Profile("sandbox")
@ConditionalOnProperty(prefix = "app.sandbox.seed", name = "enabled", havingValue = "true")
public @interface SandboxSeedComponent {
}
