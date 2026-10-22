package br.com.gestorcobranca.identity.application.port.in;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.MfaEnrollment;

/** Operações do usuário autenticado sobre a própria conta. */
public interface MyAccountUseCases {

    AuthenticatedUserView profile();

    /** Troca de senha com confirmação da senha atual; encerra as demais sessões. */
    void changePassword(String currentPassword, String newPassword);

    MfaEnrollment startMfaEnrollment();

    void confirmMfaEnrollment(String code);
}
