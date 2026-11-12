package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Duration;

/**
 * Prazo da janela de novidade dos contatos recebidos numa carga para devedor já existente, por assessoria
 * (docs/tecnico/desenho/contatos-recebidos.md §2). Enquanto não gravado, vale o padrão do sistema
 * ({@code app.debt.contact-novelty.window-days}). Vale para os contatos recebidos depois da alteração: a janela de um
 * contato já recebido não muda.
 */
@Entity
@Table(name = "contact_novelty_settings", schema = "debt")
public class ContactNoveltySettings extends TenantScopedEntity {

    public static final int MIN_WINDOW_DAYS = 1;
    public static final int MAX_WINDOW_DAYS = 180;

    @Column(name = "window_days", nullable = false)
    private int windowDays;

    protected ContactNoveltySettings() {
    }

    public ContactNoveltySettings(int windowDays) {
        change(windowDays);
    }

    /** @return true se o prazo mudou */
    public boolean change(int windowDays) {
        requireValid(windowDays);
        boolean changed = this.windowDays != windowDays;
        this.windowDays = windowDays;
        return changed;
    }

    public static void requireValid(int windowDays) {
        Guard.require(windowDays >= MIN_WINDOW_DAYS && windowDays <= MAX_WINDOW_DAYS, "contact_novelty.window_invalid",
                "Janela de novidade entre " + MIN_WINDOW_DAYS + " e " + MAX_WINDOW_DAYS + " dias");
    }

    public int getWindowDays() {
        return windowDays;
    }

    public Duration window() {
        return Duration.ofDays(windowDays);
    }
}
