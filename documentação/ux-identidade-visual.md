# Identidade visual e onboarding

Como o Gestor de Cobrança se apresenta e como ensina o próprio uso. Detalhes técnicos em [`docs/design/ux.md`](../docs/design/ux.md).

## Objetivo

Uma interface **leve e fácil de operar** durante turnos longos, que funcione no computador da operação e no celular do gestor, e que **ensine sozinha**: quem chega recebe um tour rápido; quem já conhece é avisado do que mudou; ninguém é interrompido sem poder dispensar.

## Para quem desenhamos

| Perfil | O que precisa da interface |
| --- | --- |
| Negociador (júnior e sênior) | Rapidez, teclado, pouca rolagem, orientação na própria tela, nada que atrapalhe o atendimento |
| Supervisor | Ver exceções de relance, aprovar com segurança |
| Gestor | Consultar números no computador ou no celular |
| Financeiro | Valores fáceis de conferir, proteção contra erro em estornos e baixas |
| Auditor | Rastros legíveis, sem exposição de dados pessoais |
| Administrador | Saber por onde começar a implantação |

## Identidade visual

- **Cores**: azul institucional como cor principal, cinzas frios nas superfícies. Cores de estado (verde, amarelo, vermelho, azul, cinza) **sempre acompanhadas de texto e ícone**, para quem tem daltonismo e para leitura rápida.
- **Tema claro e escuro**: segue o sistema operacional por padrão; cada pessoa pode fixar claro ou escuro. O escuro reduz o brilho em jornadas longas e ambientes com pouca luz.
- **Densidade**: *confortável* (padrão) ou *compacta*, que mostra mais linhas por tela para quem trabalha com alto volume. Em telas de toque a densidade compacta é ignorada para manter os alvos grandes.
- **Tipografia**: Inter, fonte de código aberto projetada para telas, servida pela própria aplicação (sem serviços externos). Números alinhados em colunas para facilitar a conferência de valores.
- **Preferências que acompanham a pessoa**: tema, densidade e menu recolhido ficam salvos na conta e valem em qualquer computador ou celular.

## Navegação

- **Menu lateral** só com o que o perfil pode usar; pode ser recolhido para uma faixa de ícones e ganhar espaço.
- **Cabeçalho** mostra a assessoria e o escopo de credores do acesso, para ninguém trabalhar no contexto errado.
- **Busca rápida (Ctrl + K)**: digite parte do nome de uma tela ou ação (ex.: "auditoria", "tema escuro") e pressione Enter.
- **Trilha de navegação** no topo de cada tela (Início › Administração › Usuários).
- **Atalhos de teclado**: a tecla **?** mostra a lista; a estação de cobrança terá atalhos próprios.

## Onboarding

| Situação | O que acontece |
| --- | --- |
| Primeiro acesso | Convite para um tour de 1 minuto pela navegação |
| Tela aberta pela primeira vez | Convite para um tour curto daquela tela (2 a 4 passos) |
| Tela que mudou desde a última visita | Cartão "Novidades nesta tela" com o que mudou |
| Nova versão do sistema | Selo "Novo" na Ajuda e central de **Novidades** |
| Implantação | Checklist **Primeiros passos** conforme o perfil |

Regras de convivência:

- Os convites aparecem no canto da tela, **nunca abrem um tour sozinhos** e cada um aparece uma única vez.
- Todo tour pode ser fechado a qualquer momento (botão ou tecla Esc) e refeito pela **Ajuda**.
- "Não mostrar dicas automáticas" desliga os convites para a pessoa; a Ajuda continua disponível.
- O progresso fica salvo na conta: quem começa no computador do escritório não revê o tour no notebook.

Checklists de primeiros passos:

| Perfil | Passos |
| --- | --- |
| Administrador | Ativar verificação em duas etapas → revisar configurações da assessoria → cadastrar credor → criar carteira → cadastrar usuários → organizar equipes |
| Negociador | Ativar verificação em duas etapas → conhecer a navegação → abrir a fila de trabalho → consultar a ficha de um devedor → registrar a primeira interação |
| Auditor | Ativar verificação em duas etapas → consultar o log de acesso → verificar a trilha de auditoria |

Passos de módulos ainda não publicados (fila de trabalho, ficha do devedor) aparecem automaticamente quando o módulo entra no menu. Itens como "ativar verificação em duas etapas" e "revisar configurações" se marcam sozinhos; os demais a própria pessoa marca ao concluir.

## Acessibilidade

Seguimos as diretrizes **WCAG 2.2 nível AA**: contraste alto em ambos os temas, foco sempre visível, tudo operável pelo teclado, compatibilidade com leitores de tela, respeito à opção do sistema de reduzir animações e nada que dependa só de cor.

## Proteção de dados

- CPF e CNPJ de devedores aparecem **mascarados** nas listas; exibir o número completo depende de permissão específica e fica registrado.
- O onboarding guarda apenas o identificador da pessoa e quais tours e telas ela já viu — nenhum dado pessoal ou de devedor.
- Ações financeiras e irreversíveis pedem confirmação com o resumo do que será feito e, quando irreversíveis, a digitação de uma palavra de confirmação.

## Decisões tomadas sem o responsável (para revisão)

| # | Decisão | Alternativa considerada | Motivo |
| --- | --- | --- | --- |
| 1 | Manter Material 3 com tokens próprios | Biblioteca de componentes diferente | Já adotado no projeto, acessível e com suporte a temas |
| 2 | Tema do sistema como padrão, com claro/escuro opcional | Só tema claro | Conforto em turnos longos; preferência por pessoa |
| 3 | Densidade confortável como padrão, compacta opcional | Compacta para todos | Legibilidade para iniciantes; volume para experientes |
| 4 | Fonte Inter servida pela aplicação | Fonte do sistema ou CDN do Google | Legibilidade uniforme; sem serviço externo (segurança e privacidade) |
| 5 | Tour próprio sobre o Angular CDK | Bibliotecas Shepherd.js / Intro.js | Licenças AGPL/comerciais incompatíveis; sem nova dependência |
| 6 | Progresso do onboarding salvo no servidor | Salvar só no navegador | Acompanha a pessoa entre dispositivos |
| 7 | Convites dispensáveis em vez de tours automáticos | Tour obrigatório no primeiro acesso | Não interromper o atendimento |
| 8 | Busca rápida (Ctrl + K) e atalhos de teclado | Só menu | Produtividade da operação |
