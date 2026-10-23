package br.com.gestorcobranca.shared.jobs;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Apoio a testes de integração: com o agendamento desligado ({@code app.scheduling.enabled=false}),
 * executa o {@link JobWorker} sob demanda até não restarem jobs prontos do tipo informado.
 */
public final class JobDrain {

    private JobDrain() {
    }

    public static void drain(ApplicationContext context, JdbcTemplate jdbc, String typePrefix) {
        JobWorker worker = context.getBean(JobWorker.class);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            worker.poll();
            Long open = jdbc.queryForObject("""
                    select count(*) from shared.processing_job
                     where type like ? and (status = 'RUNNING' or (status = 'PENDING' and run_at <= now()))
                    """, Long.class, typePrefix + "%");
            if (open != null && open == 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("Jobs " + typePrefix + "* não terminaram a tempo");
    }
}
