# Decisões tomadas sem consulta (sessão autônoma de 22/10/2026 22h40 → 23/10/2026)

O responsável pelo produto ficou indisponível por ~10 h e pediu que as decisões impeditivas fossem tomadas com base em padrões de mercado. Cada item abaixo pode ser revisto; os marcados **[revisar]** merecem atenção prioritária.

| # | Módulo | Decisão | Alternativas consideradas | Motivo |
| --- | --- | --- | --- | --- |
| 1 | processo | Continuar integrando direto na master após `mvn verify` verde (regra vigente "até eu avisar"). | Abrir PRs para revisão posterior. | Instrução vigente do responsável. |
| 2 | processo | Não reescrever o histórico já publicado da master para corrigir commits "Unverified". | `commit --amend` + force push. | Operação irreversível para clones existentes; exige autorização explícita. |
