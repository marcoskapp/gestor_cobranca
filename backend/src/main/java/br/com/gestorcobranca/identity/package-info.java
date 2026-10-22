/**
 * Identidade e acesso (F01, F02, M09): usuários, perfis e permissões, equipes, escopo por
 * credor, autenticação com MFA, sessões com refresh token rotativo, consulta de log de acesso e
 * trilha de auditoria, e provisionamento de novas assessorias.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"shared", "organization :: usecases"})
package br.com.gestorcobranca.identity;

import org.springframework.modulith.ApplicationModule;
