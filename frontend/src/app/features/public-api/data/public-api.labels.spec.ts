import { SCOPE_PERMISSIONS } from '../tokens/token-dialog';
import { formatBytes, parseIpList } from './public-api.labels';
import { Permission } from '../../../core/auth/permission';

describe('rótulos da API pública', () => {
  it('lista de IPs aceita linhas, vírgulas e ponto e vírgula, sem vazios nem repetidos', () => {
    expect(parseIpList(' 200.10.0.0/24\n\n189.1.2.3, 189.1.2.3; 2001:db8::/48 ')).toEqual(['200.10.0.0/24', '189.1.2.3', '2001:db8::/48']);
    expect(parseIpList('')).toEqual([]);
  });

  it('bytes em unidades decimais, como no faturamento', () => {
    expect(formatBytes(999)).toBe('999 B');
    expect(formatBytes(1_500_000)).toBe('1,5 MB');
    expect(formatBytes(2_000_000_000)).toBe('2 GB');
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
