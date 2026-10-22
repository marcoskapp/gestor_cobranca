package br.com.gestorcobranca.shared.config;

import br.com.gestorcobranca.shared.tenancy.TenantAwareJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Substitui o gerenciador de transações padrão pelo {@link TenantAwareJpaTransactionManager},
 * que propaga tenant e ator para o PostgreSQL (RLS) em toda transação — inclusive as usadas por
 * {@code JdbcTemplate}, que compartilha a conexão da transação JPA.
 */
@Configuration
@EnableTransactionManagement
class PersistenceConfig {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }
}
