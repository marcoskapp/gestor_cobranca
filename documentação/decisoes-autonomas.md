# Decisões tomadas sem consulta (sessão autônoma de 22/10/2026 22h40 → 23/10/2026)

O responsável pelo produto ficou indisponível por ~10 h e pediu que as decisões impeditivas fossem tomadas com base em padrões de mercado. Cada item abaixo pode ser revisto; os marcados **[revisar]** merecem atenção prioritária.

| # | Módulo | Decisão | Alternativas consideradas | Motivo |
| --- | --- | --- | --- | --- |
| 1 | processo | Continuar integrando direto na master após `mvn verify` verde (regra vigente "até eu avisar"). | Abrir PRs para revisão posterior. | Instrução vigente do responsável. |
| 2 | processo | Não reescrever o histórico já publicado da master para corrigir commits "Unverified". | `commit --amend` + force push. | Operação irreversível para clones existentes; exige autorização explícita. |
| 3 | incentive (novo) | Criado o módulo `incentive` para métricas e comissão do **negociador**, separado de `settlement` (comissão da **assessoria** junto ao credor). Desenho em `docs/design/remuneracao-variavel.md`. | Incluir no `settlement` ou no `analytics`. | Domínios, públicos e permissões diferentes; mesma base (recuperação confirmada) garante coerência. |
| 4 | incentive | Comissão só é apurada sobre pagamento confirmado/conciliado, após carência de 5 dias úteis; prevista no acordo apenas como informação. | Pagar por acordo formalizado. | RB08 e padrão de mercado; evita premiar acordos que não pagam. |
| 5 | incentive **[revisar]** | Pesos de crédito com IA: copiloto 100% humano; handoff em que o humano negociou 100%; handoff só confirmado 30%; assistente autônomo 0% individual → pool do supervisor/equipe; portal/campanha 50% para o último humano com contato efetivo em 7 dias. Todos parametrizáveis. | Dividir sempre meio a meio; não creditar IA a ninguém. | Remunera quem gerou o resultado, não penaliza o uso do assistente e remunera quem supervisiona a IA. |
| 6 | incentive **[revisar — jurídico/RH]** | Sem estorno de comissão já aprovada por padrão (CLT art. 457; Lei 3.207/57 art. 7º); estorno só se habilitado no plano. Desligamento: crédito até a data + carência. | Clawback automático. | Risco trabalhista; precisa de validação. |
| 7 | incentive | Plano semeado `PADRAO_MERCADO` (1,5/2,0/2,5% escalonado; multiplicadores por atraso 1,0–1,8; gatilho 80%, acelerador 1,2× acima de 110%; deflatores de quebra e de uso do envelope; portão de monitoria 7,0). | Plano vazio. | Ponto de partida editável; números a calibrar. |
| 8 | incentive | Modelo "originador" por padrão (parcelas futuras ficam com quem fechou); modelo "carteira" disponível. Renegociação conta só o ganho incremental, peso 50% quando o acordo quebrado era do próprio negociador. | Sempre o responsável atual. | Padrão de mercado; evita incentivo a quebrar e refazer. |
| 9 | incentive | Aprovação do extrato com segregação: gestor fecha/ajusta, financeiro aprova; exportação CSV/XLSX para folha (sem integração com folha). | Integração com sistema de folha. | Sem serviço externo aprovado; reduz risco. |
| 10 | UX | Material 3 com tokens próprios; tema segue o sistema e pode ser fixado por usuário; densidade confortável por padrão (compacta opcional, ignorada em toque); fonte Inter self-hosted (removido Google Fonts da CSP). Detalhes em `documentação/ux-identidade-visual.md`. | Outra biblioteca de componentes; CDN de fontes. | Acessibilidade, conforto em turnos longos, segurança. |
| 11 | UX | Onboarding com motor de tour próprio sobre Angular CDK (sem nova dependência); convites dispensáveis, nunca tour obrigatório; tours versionados por tela ("Novidades nesta tela"); checklist por perfil; progresso salvo no servidor (`/api/v1/me/ui-state`). | Shepherd/Intro.js (AGPL/comercial), Driver.js; salvar no navegador. | Licença, integração com o Angular, não interromper o atendimento, acompanha o usuário entre dispositivos. |
| 12 | UX | Menu "Configurações da assessoria" renomeado para "Configurações"; busca rápida Ctrl/⌘+K e atalhos de teclado (lista em "?"). | — | Clareza e produtividade. |
| 13 | incentive | Regras do plano como records Java tipados em jsonb com versão de esquema; vigência em meses inteiros; pesos de crédito fixados na data do acordo; taxas/metas da versão do período. | Tabelas por componente; datas livres. | Tipagem sem dezenas de tabelas; um período = uma versão. |
| 14 | incentive | Combinação no motor: multiplicador de dificuldade sobre a comissão proporcional; deflatores somados; acelerador sobre o total (opção: só excedente); portão de monitoria suspende o variável até nova avaliação. | Deflatores multiplicativos; zerar no portão. | Mais fácil de explicar ao negociador; texto do desenho. |
| 15 | incentive | Ajuste manual de extrato acima de R$ 500 exige dupla aprovação; quem aprova não pode ser o beneficiário nem quem fechou/ajustou. Agregados em logs só com ≥ 3 extratos. | Duas aprovações sempre. | Segregação de funções e privacidade da remuneração. |
| 16 | incentive | Dependência `incentive → compliance` (calendário de feriados) acrescentada ao grafo. Ocupação = tempo de chamadas atendidas ÷ tempo logado (conversas não têm duração). | Interface no shared. | Porta pública já existente, sem ciclo. |
| 17 | processo | Migration do incentive no identity renumerada para V0202 (V0201 já usada pelo estado de interface). | — | Conflito de versão entre frentes paralelas. |

## Situação ao fim da sessão autônoma

- **Integrados na master (testes verdes):** compliance, UX (identidade visual + onboarding + `/api/v1/me/ui-state`), incentive (métricas e remuneração variável dos negociadores).
- **Interrompidos pelo responsável e não retomados** (trabalho não commitado preservado nos worktrees, nada foi descartado):
  - `wt-debt-garantias` (garantias, coobrigados, prescrição) — 60 arquivos alterados, sem commit;
  - `wt-modulo-ingestion` — 4 arquivos alterados, sem commit;
  - `wt-modulo-collection` — 2 arquivos alterados, sem commit;
  - `wt-frontend-debt-compliance` — 6 arquivos alterados, sem commit;
  - `wt-negotiation-motor` — sem alterações.
- Próxima onda (negotiation parte 2, payment, communication, settlement…) **não foi iniciada**, aguardando orientação.
