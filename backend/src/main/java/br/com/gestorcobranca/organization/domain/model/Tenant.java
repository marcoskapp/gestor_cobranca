package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.BaseEntity;
import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * Assessoria de cobrança contratante da plataforma. É a raiz do isolamento multi-tenant: todos
 * os dados operacionais referenciam um tenant. A própria tabela não tem RLS, pois precisa ser
 * consultada antes da autenticação (resolução do tenant no login); o acesso é controlado pelos
 * casos de uso.
 */
@Entity
@Table(name = "tenant", schema = "organization")
public class Tenant extends BaseEntity {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,48}[a-z0-9])");

    @Column(name = "slug", nullable = false, unique = true, length = 50)
    private String slug;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "trade_name", nullable = false, length = 200)
    private String tradeName;

    @Column(name = "cnpj", nullable = false, length = 14)
    private String cnpj;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status;

    /** Operador da plataforma SaaS (único tenant com permissão TENANT_MANAGE). */
    @Column(name = "platform", nullable = false)
    private boolean platform;

    /** Janela padrão de contato com devedores (limites de horário por canal podem ser mais restritos). */
    @Column(name = "contact_window_start", nullable = false)
    private LocalTime contactWindowStart;

    @Column(name = "contact_window_end", nullable = false)
    private LocalTime contactWindowEnd;

    @Column(name = "contact_on_saturday", nullable = false)
    private boolean contactOnSaturday;

    @Column(name = "contact_on_sunday", nullable = false)
    private boolean contactOnSunday;

    protected Tenant() {
    }

    public Tenant(String slug, String legalName, String tradeName, String cnpj, boolean platform) {
        Guard.require(slug != null && SLUG.matcher(slug).matches(), "tenant.slug_invalid",
                "Identificador deve ter 3 a 50 caracteres: letras minúsculas, números e hífen");
        this.slug = slug;
        this.legalName = requireText(legalName, "tenant.legal_name_required");
        this.tradeName = requireText(tradeName, "tenant.trade_name_required");
        this.cnpj = br.com.gestorcobranca.shared.domain.Document.parse(cnpj).number();
        this.platform = platform;
        this.status = TenantStatus.ACTIVE;
        this.contactWindowStart = LocalTime.of(8, 0);
        this.contactWindowEnd = LocalTime.of(20, 0);
        this.contactOnSaturday = true;
        this.contactOnSunday = false;
    }

    public void rename(String legalName, String tradeName) {
        this.legalName = requireText(legalName, "tenant.legal_name_required");
        this.tradeName = requireText(tradeName, "tenant.trade_name_required");
    }

    public void defineContactWindow(LocalTime start, LocalTime end, boolean saturday, boolean sunday) {
        Guard.require(start != null && end != null && start.isBefore(end), "tenant.contact_window_invalid",
                "Início da janela de contato deve ser anterior ao fim");
        this.contactWindowStart = start;
        this.contactWindowEnd = end;
        this.contactOnSaturday = saturday;
        this.contactOnSunday = sunday;
    }

    public void suspend() {
        Guard.requireState(!platform, "tenant.platform_immutable", "O tenant da plataforma não pode ser suspenso");
        this.status = TenantStatus.SUSPENDED;
    }

    public void reactivate() {
        this.status = TenantStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    private static String requireText(String value, String code) {
        Guard.require(value != null && !value.isBlank(), code, "Campo obrigatório");
        return value.trim();
    }

    public String getSlug() {
        return slug;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public String getCnpj() {
        return cnpj;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public boolean isPlatform() {
        return platform;
    }

    public LocalTime getContactWindowStart() {
        return contactWindowStart;
    }

    public LocalTime getContactWindowEnd() {
        return contactWindowEnd;
    }

    public boolean isContactOnSaturday() {
        return contactOnSaturday;
    }

    public boolean isContactOnSunday() {
        return contactOnSunday;
    }
}
