# Segurança — registro de riscos

Riscos de segurança **conhecidos e ainda não eliminados**: vulnerabilidades abertas (até a produção, decisão 1383),
controles que dependem de aprovação do responsável e riscos aceitos formalmente. Os controles já implementados ficam
em [seguranca.md](seguranca.md); as vulnerabilidades corrigidas e validadas, no histórico deste arquivo (§5) e no
commit da correção (a revisão independente de 25/10/2026 continua em [seguranca.md](seguranca.md) §7). Quem mantém este registro é a sessão principal, a partir dos
relatórios do subagente `engenheiro-seguranca` ([.claude/agents/engenheiro-seguranca.md](../../.claude/agents/engenheiro-seguranca.md)).

## 1. O que entra aqui

Até a entrada em produção (decisão 1383), **todos** os achados de segurança confirmados ficam neste arquivo, inclusive
as vulnerabilidades abertas Críticas e Altas, com evidência, cenário de exploração e correção, para que todas as
frentes de trabalho enxerguem o mesmo quadro. Quando o software for para produção, as vulnerabilidades abertas passam a
ficar fora do repositório (security advisory privado no GitHub) e este arquivo volta a registrar só o controle que
falta.

| Situação | Onde fica |
| --- | --- |
| Vulnerabilidade aberta (qualquer severidade) | §4, com o identificador `SEG-AAAAMMDD-NN` do relatório do `engenheiro-seguranca` |
| Controle que depende de aprovação (serviço AWS, ferramenta, custo) | §3, com o número da issue |
| Risco aceito formalmente | §3, com quem aceitou, quando, o motivo e a data de reavaliação |
| Vulnerabilidade corrigida e validada | sai da §4 para o histórico (§5), com o commit e o teste que a cobre; o controle vai para [seguranca.md](seguranca.md) |

Mesmo antes da produção: nunca registrar segredo, token, senha, dado pessoal ou dado real de cliente, nem em exemplo;
prova de conceito só com usuários e dados fictícios do sandbox.

## 2. Estados

`aberto` (confirmado, sem correção) → `em correção` → `corrigido` (só depois da validação do `engenheiro-seguranca`,
com teste que falharia sem a correção) — ou `aguardando aprovação` (depende de issue) — ou `aceito` (decisão do
responsável, com prazo de reavaliação). Suspeita sem evidência não entra no registro.

## 3. Riscos abertos

| ID | Risco (controle que falta) | Severidade | Estado | Mitigação atual | Referência |
| --- | --- | --- | --- | --- | --- |
| R-01 | Sem WAF à frente do ALB nem limite global por token entre instâncias na API pública | Média | aguardando aprovação | limite de taxa por instância, bloqueio progressivo no login, limites nos webhooks | issues #5, #17, #18 |
| R-02 | Segredos de produção sem cofre gerenciado e sem rotação automática | Média | aguardando aprovação | variáveis de ambiente/arquivos `0600`; caminhos `app/*` × `migration/*` separados | issues #6, #8 |
| R-03 | Sem detecção de ameaças na conta AWS | Média | aguardando aprovação | logs no CloudWatch e alarmes | issues #11, #7 |
| R-04 | Documentos de sistemas externos (portal, API, e-mail) sem antivírus | Média | aguardando aprovação | conferência de tipo pelo conteúdo, limites de tamanho e quarentena para origem externa | issue #33; decisões 816 e 819 |
| R-05 | Webhook Pix sem mTLS no ALB | Média | aguardando aprovação | mTLS só com `app.payment.webhook.mtls-enabled`; cabeçalhos `X-Amzn-Mtls-*` descartados fora do ALB | issue #30 |
| R-06 | AAD da criptografia de campo sem tabela/coluna/id (só a assessoria) | Baixa | aberto — aceite formal pendente do responsável (limitação do converter JPA) | AAD com a assessoria (formato v2) | [seguranca.md](seguranca.md) §7, achado A10 |
| R-07 | Sem varredura automatizada de dependências, segredos e código (SCA, secret scanning, SAST) nem CI | Média | aguardando aprovação | revisão pelo `revisor-de-codigo` e pelo `engenheiro-seguranca`; `npm audit` manual | issues #10, #28 e #34 |
| R-09 | Vulnerabilidades abertas descritas (evidência e exploração) em repositório público | Média | aceito — responsável, 24/11/2026: todas as frentes enxergam o mesmo quadro e não há dado real em produção | nenhum segredo, token ou dado pessoal no registro; prova de conceito só com dados fictícios do sandbox; issue pública nunca descreve vulnerabilidade aberta | decisão 1383; reavaliar antes da entrada em produção (abertas → security advisory privado) |
| R-08 | Sem teste de intrusão independente antes da produção | Média | aguardando aprovação | revisão independente de 25/10/2026 ([seguranca.md](seguranca.md) §7) | [seguranca.md](seguranca.md) §8 |

## 4. Vulnerabilidades abertas

Preenchido a partir das auditorias e revisões do `engenheiro-seguranca`. Achado de auditoria: detalhe completo no
relatório da auditoria (pasta `seguranca-auditorias/`, um arquivo por auditoria). Achado de revisão antes do commit: a
evidência, o cenário de exploração e a correção ficam no próprio item, abaixo da tabela.

| ID | Título | Severidade | Estado | Onde | Correção prevista |
| --- | --- | --- | --- | --- | --- |

## 5. Histórico de correções

| ID | Título | Severidade | Corrigido em (commit) | Teste que cobre | Validado em |
| --- | --- | --- | --- | --- | --- |
