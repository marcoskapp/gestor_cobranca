package br.com.gestorcobranca.shared.manual;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Parâmetros da aplicação citados no manual, nos tours, nas novidades e em textos de tela (prazos, limites e
 * quantidades de {@code app.*}), implementado pelo módulo dono da configuração
 * (docs/tecnico/desenho/ux.md §8.7). É linguagem publicada do kernel, como
 * {@code shared.pendingwork.PendingWorkSource}: o agregador (módulo {@code helpdesk}) recebe todas as
 * implementações por injeção e não depende dos módulos que as fornecem.
 * <p>
 * Regras para quem implementa:
 * <ul>
 *   <li>Só números de configuração da aplicação, iguais para todas as assessorias — nunca dado de assessoria,
 *       credor, devedor ou usuário, nem segredo (a leitura é liberada a todo usuário interno).</li>
 *   <li>Os valores são os que o módulo de fato aplica (lidos das próprias {@code *Properties}), na unidade que o
 *       nome indica ({@code ...Days}, {@code ...Seconds}, {@code ...Mb}); um prazo que não cabe na unidade é
 *       recusado na subida pelas {@code *Properties}, para o texto nunca mostrar número arredondado.</li>
 *   <li>A chave ({@code <modulo>.<nomeEmCamelCase>}) é contrato com o conteúdo do frontend
 *       ({@code core/onboarding/manual/manual-parameters.ts}) e com a base de conhecimento do chat: não renomear.</li>
 * </ul>
 */
public interface ManualParameterSource {

    /** Módulo dono, prefixo de todas as chaves (ex.: {@code debt}). */
    String module();

    /** Valores pela chave sem o prefixo (ex.: {@code cumulativeWindowDays} → 30). */
    Map<String, BigDecimal> parameters();
}
