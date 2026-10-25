package br.com.gestorcobranca.portal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisão de segurança S3: o envio do código do portal acontece fora da requisição, para que o tempo de
 * resposta do pedido não revele se o documento tem dívida no portal. Aqui o executor do envio é retido
 * até o teste liberá-lo: a resposta chega antes de qualquer envio.
 */
class PortalCodeDispatchIT extends PortalTestSupport {

    static final HeldExecutor HELD = new HeldExecutor();

    @TestBean(name = "portalCodeDispatchExecutor", methodName = "heldExecutor")
    Executor dispatchExecutor;

    static Executor heldExecutor() {
        return HELD;
    }

    @BeforeEach
    void setUp() {
        provision("pdisp");
    }

    @Test
    void codeIsSentAfterTheResponseWithTheSameWorkForExistingAndUnknownDocuments() {
        UUID real = requestCode(CPF_MARIA);
        UUID decoy = requestCode("870.424.856-29");

        // respondeu sem enviar: nada saiu ainda para o documento existente
        assertThat(count("select count(*) from portal.access_challenge where id = ? and message_id is null", real)).isEqualTo(1);
        assertThat(HELD.pending()).isEqualTo(1);

        HELD.runAll();
        assertThat(sentCode(real)).matches("\\d{6}");
        assertThat(count("select count(*) from portal.access_challenge where id = ? and message_id is null", decoy)).isEqualTo(1);
    }

    /** Executor que guarda as tarefas até {@link #runAll()}. */
    static final class HeldExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        synchronized int pending() {
            return tasks.size();
        }

        void runAll() {
            List<Runnable> batch;
            synchronized (this) {
                batch = new ArrayList<>(tasks);
                tasks.clear();
            }
            batch.forEach(Runnable::run);
        }
    }
}
