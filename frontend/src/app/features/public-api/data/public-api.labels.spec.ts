import { SCOPE_PERMISSIONS } from '../tokens/token-dialog';
import { billableMegabytes, formatBytes, formatCycle, parseIpList, prorationLabel } from './public-api.labels';
import { Permission } from '../../../core/auth/permission';

describe('rótulos da API pública', () => {
  it('lista de IPs aceita linhas, vírgulas e ponto e vírgula, sem vazios nem repetidos', () => {
    expect(parseIpList(' 200.10.0.0/24\n\n189.1.2.3, 189.1.2.3; 2001:db8::/48 ')).toEqual(['200.10.0.0/24', '189.1.2.3', '2001:db8::/48']);
    expect(parseIpList('')).toEqual([]);
  });

  it('bytes em unidades binárias, como no faturamento (1 MB = 1.048.576 bytes)', () => {
    expect(formatBytes(1023)).toBe('1.023 B');
    expect(formatBytes(1_572_864)).toBe('1,5 MB');
    expect(formatBytes(2 * 1024 ** 3)).toBe('2 GB');
  });

  it('MB faturáveis inteiros, arredondados para cima', () => {
    expect(billableMegabytes(0)).toBe(0);
    expect(billableMegabytes(1)).toBe(1);
    expect(billableMegabytes(1_048_576)).toBe(1);
    expect(billableMegabytes(1_048_577)).toBe(2);
  });

  it('proporcionalidade só no ciclo de transição', () => {
    expect(prorationLabel(31, 31)).toBeNull();
    expect(prorationLabel(10, 28)).toBe('10 de 28 dias (fator 0,3571)');
  });

  it('período do ciclo em datas brasileiras', () => {
    expect(formatCycle('2026-02-01', '2026-02-28')).toBe('01/02/2026 a 28/02/2026');
  });

  it('escopos concedem só permissões conhecidas e nunca dados sem máscara', () => {
    const known = new Set<string>(Object.values(Permission));
    for (const [scope, permissions] of Object.entries(SCOPE_PERMISSIONS)) {
      expect(scope).toMatch(/^[a-z]+:(read|write)$/);
      for (const p of permissions) {
        expect(known.has(p), `${scope} → ${p}`).toBe(true);
      }
      expect(permissions).not.toContain(Permission.DEBTOR_SENSITIVE_VIEW);
    }
    expect(Object.keys(SCOPE_PERMISSIONS)).toHaveLength(14);
  });
});
