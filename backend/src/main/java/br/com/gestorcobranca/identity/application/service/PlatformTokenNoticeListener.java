package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.publicapi.PlatformTokensControlled;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aviso por e-mail à assessoria quando a plataforma pausa, retoma ou revoga tokens de integração (ou a retomada
 * agendada vence) — decisão 770. Destinatários: usuários ativos com {@code PUBLIC_API_TOKEN_MANAGE} (o administrador e
 * quem cuida das integrações). Usa o alerta de segurança do {@link IdentityMailer} (enviado depois do commit, falha
 * nunca desfaz a ação). O e-mail identifica os tokens só pelo prefixo público e remete à tela do token, onde estão o
 * motivo e a retomada agendada — o motivo não sai do sistema. Idempotente por evento.
 */
@Component
class PlatformTokenNoticeListener extends TenantEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformTokenNoticeListener.class);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Sao_Paulo"));
    private static final int MAX_LISTED = 10;

    private final RoleRepository roles;
    private final UserRepository users;
    private final IdentityMailer mailer;
    private final MeterRegistry meterRegistry;

    PlatformTokenNoticeListener(RoleRepository roles, UserRepository users, IdentityMailer mailer,
                                MeterRegistry meterRegistry) {
        this.roles = roles;
        this.users = users;
        this.mailer = mailer;
        this.meterRegistry = meterRegistry;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PlatformTokensControlled event) {
        handleOnce(event, "identity.platform-token-notice", () -> notify(event));
    }

    private void notify(PlatformTokensControlled event) {
        Set<UUID> roleIds = roles.findAll().stream().filter(Role::isActive)
                .filter(r -> r.getPermissions().contains(Permission.PUBLIC_API_TOKEN_MANAGE))
                .map(Role::getId).collect(Collectors.toSet());
        List<User> recipients = roleIds.isEmpty() ? List.of() : users.findActiveByRoleIds(roleIds);
        String subject = subject(event);
        String message = message(event);
        for (User user : recipients) {
            mailer.sendSecurityAlert(user.getEmail(), user.getName(), subject, message);
        }
        meterRegistry.counter("identity.platform_token_notices", "action", event.action().toLowerCase(java.util.Locale.ROOT))
                .increment(recipients.size());
        LOG.atInfo().addKeyValue("action", event.action()).addKeyValue("tokens", event.tokenIds().size())
                .addKeyValue("recipients", recipients.size())
                .log("Aviso de controle de tokens pela plataforma enviado à assessoria");
    }

    static String subject(PlatformTokensControlled event) {
        boolean many = event.tokenIds().size() > 1;
        return switch (event.action()) {
            case PlatformTokensControlled.PAUSED -> many ? "Tokens de integração pausados pela plataforma"
                    : "Token de integração pausado pela plataforma";
            case PlatformTokensControlled.RESUMED, PlatformTokensControlled.AUTO_RESUMED ->
                    many ? "Tokens de integração retomados" : "Token de integração retomado";
            case PlatformTokensControlled.REVOKED -> "Token de integração revogado pela plataforma";
            default -> "Tokens de integração alterados pela plataforma";
        };
    }

    static String message(PlatformTokensControlled event) {
        List<String> prefixes = event.tokenPrefixes();
        String listed = prefixes.stream().limit(MAX_LISTED).collect(Collectors.joining(", "))
                + (prefixes.size() > MAX_LISTED ? " e mais " + (prefixes.size() - MAX_LISTED) : "");
        String tokens = (prefixes.size() > 1 ? "Os tokens " : "O token ") + listed;
        String text = switch (event.action()) {
            case PlatformTokensControlled.PAUSED -> tokens + (prefixes.size() > 1 ? " foram pausados" : " foi pausado")
                    + " pela equipe da plataforma: as chamadas à API pública com "
                    + (prefixes.size() > 1 ? "eles" : "ele") + " estão sendo recusadas"
                    + (event.resumeAt() == null ? " até a plataforma retomar."
                    : " até " + FORMAT.format(event.resumeAt()) + ", quando voltam a ser aceitas automaticamente.");
            case PlatformTokensControlled.RESUMED -> tokens + (prefixes.size() > 1 ? " foram retomados" : " foi retomado")
                    + " pela equipe da plataforma: as chamadas voltaram a ser aceitas.";
            case PlatformTokensControlled.AUTO_RESUMED -> tokens
                    + (prefixes.size() > 1 ? " foram retomados" : " foi retomado")
                    + " automaticamente, no horário agendado pela plataforma: as chamadas voltaram a ser aceitas.";
            case PlatformTokensControlled.REVOKED -> tokens + " foi revogado pela equipe da plataforma, de forma "
                    + "definitiva: crie um novo token para a integração, se for o caso.";
            default -> tokens + " foi alterado pela equipe da plataforma.";
        };
        return text + " O motivo está na tela API pública → Tokens de integração.";
    }
}
