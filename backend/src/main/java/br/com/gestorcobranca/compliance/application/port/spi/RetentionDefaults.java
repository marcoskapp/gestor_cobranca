package br.com.gestorcobranca.compliance.application.port.spi;

import java.util.List;

/**
 * Prazos padrão do sistema das categorias de retenção descartadas pelo módulo que implementa a porta (vindos da
 * configuração dele, iguais para todas as assessorias). Cada categoria com padrão do sistema tem exatamente um
 * prazo, na unidade da categoria e dentro dos limites dela — o compliance confere na subida da aplicação.
 */
public interface RetentionDefaults {

    List<RetentionDefault> retentionDefaults();

    /**
     * @param category        categoria do compliance (ex.: {@code PORTAL_ACCESS_CHALLENGES})
     * @param amount          prazo padrão na unidade da categoria
     * @param requiredMinimum menor prazo com que o módulo dono funciona, pela configuração dele (ex.: a janela de fatos
     *                        do score); a aplicação não sobe se o mínimo aceito na tela de Retenção for menor — senão
     *                        uma assessoria poderia configurar um prazo que quebra o módulo. Zero = sem exigência
     */
    record RetentionDefault(String category, int amount, int requiredMinimum) {

        public RetentionDefault(String category, int amount) {
            this(category, amount, 0);
        }
    }
}
