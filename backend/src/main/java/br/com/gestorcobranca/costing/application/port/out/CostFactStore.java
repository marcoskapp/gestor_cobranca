package br.com.gestorcobranca.costing.application.port.out;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarning;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Fatos apurados da assessoria corrente ({@code costing.cost_daily}, {@code activity_daily}, avisos e estado). */
public interface CostFactStore {

    /**
     * Serializa a apuração da assessoria (lock da transação, esperando a apuração em andamento) e amplia o tempo limite
     * das instruções. Usado pelo job agendado.
     */
    void lockForRefresh(Duration statementTimeout);

    /**
     * Como {@link #lockForRefresh}, mas sem esperar: devolve {@code false} se outra apuração da assessoria está em
     * andamento. Usado pelas rotas HTTP, que não podem prender a requisição (nem a conexão) até o fim da outra.
     */
    boolean tryLockForRefresh(Duration statementTimeout);

    /** Substitui os fatos das categorias informadas entre {@code from} e {@code to}; devolve as linhas gravadas. */
    long replaceFacts(LocalDate from, LocalDate to, Collection<CostCategory> categories, Collection<CostFact> facts);

    void replaceWarnings(LocalDate from, LocalDate to, Collection<CostWarningCode> codes,
                         Collection<CostWarning> warnings);

    /**
     * Reconstrói a atividade (recuperado, receita da assessoria, acordos quitados, casos-dia) entre {@code from} e
     * {@code to} a partir das views de contrato; devolve as linhas gravadas.
     */
    long replaceActivity(LocalDate from, LocalDate to);

    /** Base de rateio do mês por dia × credor × carteira (só linhas com credor). */
    Map<CostKey, BigDecimal> overheadBasis(YearMonth month, OverheadAllocation allocation);

    void saveState(LocalDate from, LocalDate to, Instant refreshedAt, long durationMs, long rows, int version);

    Optional<RefreshState> state();

    record RefreshState(LocalDate lastDay, LocalDate lastFrom, Instant refreshedAt, int version) {
    }
}
