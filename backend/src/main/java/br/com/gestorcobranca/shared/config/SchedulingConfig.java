package br.com.gestorcobranca.shared.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Agendamento distribuído: {@code @Scheduled} + {@code @SchedulerLock} garante que tarefas
 * globais (selagem de auditoria, vencimento de parcelas, réguas) rodem em uma única instância
 * do Auto Scaling Group por vez. O relógio do banco é usado como referência para evitar
 * divergência entre relógios das instâncias.
 * <p>
 * Desabilitável com {@code app.scheduling.enabled=false} (testes).
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shared.shedlock")
                .usingDbTime()
                .build());
    }
}
