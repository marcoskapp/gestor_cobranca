package br.com.gestorcobranca;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com {@code app.scheduling.enabled=false} (perfil de teste) nenhum {@code @Scheduled} pode rodar:
 * jobs, selagem e republicação ficam sob controle explícito. Protege contra autoconfigurações de
 * terceiros que ativam {@code @EnableScheduling} por conta própria.
 */
class SchedulingDisabledIT extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noScheduledMethodsAreRegisteredWhenSchedulingIsDisabled() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
