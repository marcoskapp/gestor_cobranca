package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.MfaEnrollment;
import br.com.gestorcobranca.identity.application.port.in.MyAccountUseCases;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
@Transactional
class MyAccountService implements MyAccountUseCases {

    private final UserRepository users;
    private final SessionRepository sessions;
    private final CredentialService credentialService;
    private final SessionManager sessionManager;
    private final AccessProfileResolver profileResolver;
    private final TenantAdministration tenantAdministration;
    private final PasswordEncoder passwordEncoder;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    MyAccountService(UserRepository users, SessionRepository sessions, CredentialService credentialService,
                     SessionManager sessionManager, AccessProfileResolver profileResolver,
                     TenantAdministration tenantAdministration, PasswordEncoder passwordEncoder,
                     CurrentActor currentActor, AccessLogger accessLogger) {
        this.users = users;
        this.sessions = sessions;
        this.credentialService = credentialService;
        this.sessionManager = sessionManager;
        this.profileResolver = profileResolver;
        this.tenantAdministration = tenantAdministration;
        this.passwordEncoder = passwordEncoder;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUserView profile() {
        User user = currentUser();
        TenantView tenant = tenantAdministration.currentTenant();
        AccessProfileResolver.AccessProfile profile = profileResolver.resolve(user);
        return new AuthenticatedUserView(user.getId(), user.getTenantId(), tenant.slug(), tenant.tradeName(), user.getName(),
                user.getEmail(), profile.roleCodes(), profile.permissionNames(), user.isAllCreditors(),
                user.getCreditorIds(), user.isMfaEnabled());
    }

    @Override
    public void changePassword(String currentPassword, String newPassword) {
        Actor actor = currentActor.get();
        User user = currentUser();
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("reason", "wrong_current_password")));
            throw new BusinessRuleException("password.current_invalid", "Senha atual incorreta");
        }
        credentialService.changePassword(user, newPassword);
        sessions.findActiveByUser(user.getId()).stream()
                .filter(session -> !Objects.equals(session.getId(), actor.sessionId()))
                .forEach(session -> sessionManager.revoke(session, "password_changed"));
        accessLogger.log(AccessEvent.of(AccessEventType.PASSWORD_CHANGED, actor, Map.of("trigger", "self_service")));
    }

    @Override
    public MfaEnrollment startMfaEnrollment() {
        return credentialService.beginMfaEnrollment(currentUser());
    }

    @Override
    public void confirmMfaEnrollment(String code) {
        if (!credentialService.completeMfaEnrollment(currentUser(), code)) {
            throw new BusinessRuleException("mfa.invalid_code", "Código inválido. Verifique o horário do dispositivo.");
        }
        accessLogger.log(AccessEvent.of(AccessEventType.MFA_ENROLLED, currentActor.get(), Map.of()));
    }

    private User currentUser() {
        Actor actor = currentActor.get();
        return users.findById(actor.id()).orElseThrow(() -> ResourceNotFoundException.of("user", actor.id()));
    }
}
