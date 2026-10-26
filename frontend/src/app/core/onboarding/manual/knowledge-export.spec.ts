import { buildKnowledgeExport, screenAccess } from './knowledge-export';

describe('Base de conhecimento do chat de suporte (exportação do manual)', () => {
  it('acesso de cada tela pelo menu: aberta, item do menu ou contextual com as permissões do item que a contém', () => {
    expect(screenAccess('/')).toEqual({ kind: 'OPEN', permissions: [] });
    expect(screenAccess('/ajuda')).toEqual({ kind: 'OPEN', permissions: [] });
    expect(screenAccess('/cobranca/fila')).toEqual({ kind: 'MENU', permissions: ['CASE_WORK'] });
    expect(screenAccess('/devedores/:id')).toEqual({ kind: 'CONTEXT', permissions: ['DEBTOR_VIEW'] });
    expect(screenAccess(null)).toEqual({ kind: 'CONTEXT', permissions: [] });
  });

  it('exporta todas as telas do manual com caminho, acesso e só dados (nada de HTML)', async () => {
    const kb = await buildKnowledgeExport();
    expect(kb.schemaVersion).toBe(1);
    const queue = kb.screens.find((s) => s.id === 'collection.queue')!;
    expect(queue.path).toBe('/cobranca/fila');
    expect(queue.module).toBe('cobranca');
    expect(queue.objective.length).toBeGreaterThan(10);
    expect(new Set(kb.screens.map((s) => s.id)).size).toBe(kb.screens.length);
    expect(kb.glossary.some((t) => t.id === 'chat-de-suporte')).toBe(true);
    expect(JSON.stringify(kb)).not.toMatch(/<[a-z]/i);
  });
});
