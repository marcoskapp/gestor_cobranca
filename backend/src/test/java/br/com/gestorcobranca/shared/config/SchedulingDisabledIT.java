package br.com.gestorcobranca.shared.config;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com {@code app.scheduling.enabled=false} (perfil de teste) nenhum {@code @Scheduled} pode rodar.
 * Antes, a autoconfiguração do Spring Modulith Moments ligava o agendamento por conta própria: o
 * {@code JobWorker} de cada contexto de teste em cache consultava a fila compartilhada a cada
 * segundo e executava jobs de outros testes com os próprios beans — os eventos publicados ali se
 * perdiam para o teste em andamento (esperas de 60 s esgotadas de forma intermitente).
 */
class SchedulingDisabledIT extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noScheduledMethodRunsWhenSchedulingIsDisabled() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
