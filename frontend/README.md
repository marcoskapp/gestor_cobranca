# Gestor de Cobrança — frontend

SPA em Angular 22 (componentes standalone, signals, zoneless, OnPush por padrão) com Angular Material.
Consome a API REST do backend em `/api/v1`.

## Pré-requisitos

Node.js `^22.22.3 || ^24.15.0` (ver `.nvmrc`) e npm 10.

## Comandos

```bash
npm ci
npm start          # ng serve em http://localhost:4200, /api → http://localhost:8080 (proxy.conf.json)
npm run build      # build de produção em dist/gestor-cobranca-web/ (base href "/")
npm test           # testes unitários (Vitest + jsdom), sem watch
npm run lint       # ESLint (angular-eslint), deve terminar sem erros
```

O build de produção é servido como estático pelo backend, que faz fallback de rotas para
`index.html` (roteamento HTML5). O build não usa CSS crítico inline nem `<script>` inline, para
funcionar com a CSP `script-src 'self'` do backend.

## Estrutura

```
src/app/
  core/        auth (sessão, interceptor, guards, permissões), http (Problem Details, erros),
               layout (shell, menu, trilha, busca rápida, ajuda, moldura de autenticação),
               preferences (tema, densidade, estado de interface no servidor),
               onboarding (tours, convites, novidades, primeiros passos), keyboard (atalhos),
               config (locale pt-BR, Material, títulos)
  shared/      tabela paginada, estado vazio, esqueleto, valor monetário, CPF/CNPJ mascarado,
               diálogos, seletor de entidades, máscara e validação de CPF/CNPJ, pipes, status
  features/
    auth/          login, MFA, cadastro de MFA, senha expirada, esqueci a senha, redefinir/ativar
    account/       perfil, trocar senha, MFA voluntário, sessões
    admin/         usuários, perfis, equipes, unidades
    organization/  configurações da assessoria, credores, contratos, carteiras
    governance/    log de acesso, trilha de auditoria
    platform/      assessorias (TENANT_MANAGE)
    dashboard/     página inicial
```

Cada feature tem `data/` (contratos e chamadas HTTP, espelhando os records do backend) e
componentes por tela. Rotas são lazy por feature e protegidas por `authGuard` e `permissionGuard`
(`data.permissions`). A interface apenas esconde o que o usuário não pode usar: a autorização é
sempre do servidor.

## Autenticação

- Access token apenas em memória (`AuthService`); nunca em `localStorage`/`sessionStorage`.
- Refresh token em cookie `HttpOnly` `gc_refresh` (path `/api/v1/auth`), renovado por
  `POST /api/v1/auth/refresh` com `withCredentials`.
- Ao abrir a aplicação, um refresh silencioso restaura a sessão.
- Renovação proativa ~60 s antes da expiração, calculada pela vida útil do token (`exp - iat`) e
  somente se houve atividade do usuário (a sessão ociosa expira conforme o servidor).
- Em 401, uma única renovação compartilhada entre requisições concorrentes (e serializada entre
  abas pela Web Locks API, pois o refresh token é rotativo com detecção de reuso); falhando, a
  sessão é limpa e o usuário vai ao login com `returnUrl`.
- Etapas intermediárias (MFA, cadastro de MFA, senha expirada) guardam o `stepToken` apenas em
  memória; nada vai para a URL ou para o armazenamento do navegador.
- Dados persistidos no navegador: o identificador (slug) da assessoria usado no login e a
  aparência (tema e densidade, sem dado pessoal) para a tela de login e o carregamento inicial.
- Logout (`POST /api/v1/me/logout`) é propagado às outras abas por `BroadcastChannel`.

## Identidade visual e onboarding

Tokens de design em `src/styles/_tokens.scss`, tema Material 3 em `src/styles.scss` (claro, escuro
ou do sistema via `html[data-theme]`; densidade via `html[data-density]`). Tours declarativos em
`core/onboarding/content/` com âncoras `data-tour`. Guia completo e contrato para telas novas:
[`docs/design/ux.md`](../docs/design/ux.md).

## Erros

Respostas RFC 9457 são normalizadas em `core/http/problem.ts`. O `errorInterceptor` mostra o
`detail` em um snackbar (403 com mensagem de acesso negado; 5xx com o `requestId` para o
suporte). Formulários aplicam `errors[].field` aos controles com `applyServerErrors`.
