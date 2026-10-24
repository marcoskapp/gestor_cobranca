package br.com.gestorcobranca.portal.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regras da marca do credor no portal (puras):
 * <ul>
 *   <li>endereço ({@code slug}) em minúsculas, números e hífens, 3–60 caracteres, sem palavras reservadas;</li>
 *   <li>cores {@code #RRGGBB}; a primária é fundo de botão com texto branco e precisa de contraste
 *       ≥ 4,5:1 com o branco (WCAG 2.2 AA, 1.4.3); a de destaque (bordas, foco, ícones) ≥ 3:1 (1.4.11);</li>
 *   <li>logotipo PNG, JPEG ou WebP (SVG recusado: pode conter script), até 256 KiB, tipo conferido pela
 *       assinatura do conteúdo.</li>
 * </ul>
 */
public final class BrandingRules {

    public static final Set<String> LOGO_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    public static final int MAX_LOGO_BYTES = 256 * 1024;
    public static final double MIN_PRIMARY_CONTRAST = 4.5;
    public static final double MIN_ACCENT_CONTRAST = 3.0;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-F]{6}$");
    private static final Set<String> RESERVED = Set.of("api", "admin", "assets", "static", "login", "portal", "media",
            "www", "app", "suporte");

    private BrandingRules() {
    }

    public static String slug(String raw) {
        String slug = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        Guard.require(slug.length() >= 3 && slug.length() <= 60 && SLUG.matcher(slug).matches(), "portal.slug_invalid",
                "Endereço do portal: 3 a 60 caracteres com letras minúsculas, números e hífens");
        Guard.require(!RESERVED.contains(slug), "portal.slug_reserved", "Endereço reservado: " + slug);
        return slug;
    }

    public static String primaryColor(String raw) {
        String color = color(raw, "primária");
        double ratio = contrastWithWhite(color);
        if (ratio < MIN_PRIMARY_CONTRAST) {
            throw new BusinessRuleException("portal.color_contrast_insufficient",
                    "A cor primária precisa de contraste de pelo menos 4,5:1 com texto branco (atual: %.2f:1)"
                            .formatted(ratio));
        }
        return color;
    }

    public static String accentColor(String raw) {
        String color = color(raw, "de destaque");
        double ratio = contrastWithWhite(color);
        if (ratio < MIN_ACCENT_CONTRAST) {
            throw new BusinessRuleException("portal.color_contrast_insufficient",
                    "A cor de destaque precisa de contraste de pelo menos 3:1 com o fundo branco (atual: %.2f:1)"
                            .formatted(ratio));
        }
        return color;
    }

    private static String color(String raw, String label) {
        String color = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        Guard.require(COLOR.matcher(color).matches(), "portal.color_invalid", "Cor " + label + " no formato #RRGGBB");
        return color;
    }

    /** Razão de contraste WCAG entre a cor e o branco. */
    public static double contrastWithWhite(String hex) {
        double l = luminance(hex);
        return (1.0 + 0.05) / (l + 0.05);
    }

    static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Tipo do logotipo pela assinatura do conteúdo (nunca pela extensão nem pelo cabeçalho do cliente). */
    public static Optional<String> detectImageType(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }
}
