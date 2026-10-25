import { permissionGroupOf } from './permission';

describe('permissionGroupOf', () => {
  it('usa o prefixo mais longo: permissões do incentive ficam em desempenho, as do settlement em comissão', () => {
    expect(permissionGroupOf('COMMISSION_PLAN_MANAGE')).toBe('Desempenho dos negociadores');
    expect(permissionGroupOf('COMMISSION_STATEMENT_APPROVE')).toBe('Desempenho dos negociadores');
    expect(permissionGroupOf('QUALITY_EVALUATE')).toBe('Desempenho dos negociadores');
    expect(permissionGroupOf('COMMISSION_VIEW')).toBe('Comissão e prestação de contas');
    expect(permissionGroupOf('SETTLEMENT_APPROVE')).toBe('Comissão e prestação de contas');
    expect(permissionGroupOf('ALGO_NOVO')).toBe('Outras');
  });
});
