import { PaletteCommand, filterCommands, normalizeSearch } from './command-palette.service';

const cmd = (id: string, label: string, keywords: string[] = [], hint?: string): PaletteCommand => ({
  id,
  label,
  keywords,
  hint,
  group: 'Ir para',
  icon: 'x',
  run: () => undefined,
});

describe('filterCommands', () => {
  const commands = [
    cmd('a', 'Configurações da assessoria', [], 'Organização'),
    cmd('b', 'Credores', [], 'Organização'),
    cmd('c', 'Usar tema escuro', ['aparência', 'noturno']),
    cmd('d', 'Trilha de auditoria', [], 'Governança'),
  ];

  it('ignora acentos e maiúsculas', () => {
    expect(normalizeSearch('  Configurações ')).toBe('configuracoes');
    expect(filterCommands(commands, 'CONFIGURACOES').map((c) => c.id)).toEqual(['a']);
  });

  it('exige todos os termos e busca em sinônimos e seção', () => {
    expect(filterCommands(commands, 'organizacao cred').map((c) => c.id)).toEqual(['b']);
    expect(filterCommands(commands, 'noturno').map((c) => c.id)).toEqual(['c']);
  });

  it('prioriza rótulos que começam com o termo', () => {
    const result = filterCommands([cmd('x', 'Log de auditoria'), cmd('y', 'Auditoria')], 'audit');
    expect(result.map((c) => c.id)).toEqual(['y', 'x']);
  });

  it('sem termo devolve tudo', () => {
    expect(filterCommands(commands, '  ')).toHaveLength(4);
  });
});
