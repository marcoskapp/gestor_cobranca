/**
 * Sandbox de demonstração: carga de dados <strong>fictícios</strong> para subir a aplicação completa com um comando
 * ({@code scripts/sandbox/up.sh}; guia em {@code docs/tecnico/operacao/sandbox.md}).
 * <p>
 * Nada deste módulo existe em produção: todos os componentes exigem o perfil Spring {@code sandbox} e a flag
 * {@code app.sandbox.seed.enabled=true}; o {@code SandboxSeedGuard} impede a aplicação de subir se a flag for ligada
 * com o perfil {@code prod}, sem o perfil {@code sandbox} ou fora do ambiente {@code sandbox}.
 * <p>
 * A carga só usa os casos de uso públicos dos módulos (as mesmas regras de domínio, criptografia de dados pessoais,
 * blind index, auditoria e logs de negócio da operação real) — nunca SQL direto. Usuários são ativados pelo mesmo
 * fluxo de um usuário real: link de ativação recebido por e-mail (Mailpit) e definição de senha.
 * Nenhum módulo depende deste.
 */
@ApplicationModule(displayName = "Sandbox (dados fictícios de demonstração)",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases",
                "ingestion :: usecases", "collection :: usecases", "compliance :: usecases",
                "negotiation :: usecases", "payment :: usecases", "settlement :: usecases",
                "communication :: usecases", "campaign :: usecases", "legal :: usecases", "incentive :: usecases",
                "telephony :: usecases", "portal :: usecases", "assistant :: usecases", "analytics :: usecases"})
package br.com.gestorcobranca.sandbox;

import org.springframework.modulith.ApplicationModule;
