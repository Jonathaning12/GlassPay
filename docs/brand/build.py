# Genera el kit de marca de GlassPay (símbolo P-Lente, paleta Ámbar noche) desde una sola geometría.
# Uso: python3 docs/brand/build.py   (escribe logo/, app-icon/ y los iconos de iOS y Android)
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent

# Paleta Ámbar noche
INDIGO = '#141A38'      # fondo de marca
AMBER = '#FFB547'       # acento sobre fondos oscuros
AMBER_DEEP = '#A86200'  # acento sobre fondos claros (contraste >= 3:1 con crema)
CREAM = '#FFF6E8'       # fondo claro
WHITE = '#FFFFFF'

# Símbolo en viewBox 256: P cuyo cuenco es una lente Wayfarer (con reflejo) y que emite ondas.
# Caja visual: x 43-214, y 25-220 (centro 128.5, 122.5).
STEM_BOWL = 'M70 200V64H150C166 64 174 74 172 90C168 124 150 146 116 146H70'
GLINT = 'M100 114L116 98'
WAVE_IN = 'M192.5 47.4A92 92 0 0 1 192.5 160.6'
WAVE_OUT = 'M217.7 27.7A124 124 0 0 1 217.7 180.3'
OFFSET = (-13, 6)


def symbol(ink, accent=None):
    accent = accent or ink
    return (f'<g transform="translate({OFFSET[0]} {OFFSET[1]})" fill="none" stroke-linecap="round" stroke-linejoin="round">'
            f'<path d="{STEM_BOWL}" stroke="{ink}" stroke-width="28"/>'
            f'<path d="{GLINT}" stroke="{accent}" stroke-width="12"/>'
            f'<path d="{WAVE_IN}" stroke="{accent}" stroke-width="18"/>'
            f'<path d="{WAVE_OUT}" stroke="{accent}" stroke-width="18"/></g>')


def wordmark(ink, sw=26):
    # "GlassPay" construido con geometría propia (sin fuente); la P comparte el cuenco de lente.
    def arc(r, large, sweep, x, y): return f'A{r} {r} 0 {large} {sweep} {x:g} {y:g}'
    d = [f'M122.4 87.6 {arc(60, 1, 0, 140, 130)} H100', 'M184 70V190']               # G, l
    for cx in (264, 682):                                                             # a
        d.append(f'M{cx + 40} 150 {arc(40, 1, 1, cx + 40, 149.9)} M{cx + 40} 110V190')
    for cx in (369, 457):                                                             # s s
        d.append(f'M{cx + 21.7} 112.5 {arc(25, 1, 0, cx, 150)} {arc(25, 1, 1, cx - 21.7, 187.5)}')
    d.append('M528 190V70H578C594 70 602 80 600 96C596 128 580 146 554 146H528')       # P
    d.append('M760 110L794 190M828 110L774.5 236')                                    # y
    return (f'<path fill="none" stroke="{ink}" stroke-width="{sw}" stroke-linecap="round" '
            f'stroke-linejoin="round" d="{" ".join(d)}"/>')


def svg(body, w, h, bg=None, title='GlassPay'):
    back = f'<rect width="{w}" height="{h}" fill="{bg}"/>' if bg else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" role="img" aria-label="{title}">'
            f'<title>{title}</title>{back}{body}</svg>\n')


def horizontal(ink, accent):
    return svg(symbol(ink, accent) + f'<g transform="translate(262 34) scale(.8)">{wordmark(ink)}</g>', 960, 256)


def stacked(ink, accent):
    return svg(f'<g transform="translate(212 0)">{symbol(ink, accent)}</g>'
               f'<g transform="translate(120 234) scale(.52)">{wordmark(ink)}</g>', 680, 380)


def app_icon(size, bg, ink, accent, scale_ratio=0.52):
    # Cuadrado completo: iOS y Android aplican su propia máscara de esquinas.
    k = size * scale_ratio / 171
    tx, ty = size / 2 - 128.5 * k, size / 2 - 122.5 * k - size * 0.01
    body = f'<g transform="translate({tx:.2f} {ty:.2f}) scale({k:.4f})">{symbol(ink, accent)}</g>'
    return svg(body, size, size, bg=bg)


def android_foreground():
    # Vector drawable 108dp; la marca cabe en la zona segura de 66dp.
    k = 0.235
    tx, ty = 54 - 128.5 * k, 54 - 122.5 * k
    def path(d, color, width):
        return (f'        <path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="{width}"\n'
                f'            android:strokeLineCap="round" android:strokeLineJoin="round" />\n')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Símbolo GlassPay (P-Lente). Generado por docs/brand/build.py; no editar a mano. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'    <group android:translateX="{tx + OFFSET[0] * k:.3f}" android:translateY="{ty + OFFSET[1] * k:.3f}"\n'
            f'        android:scaleX="{k}" android:scaleY="{k}">\n'
            + path(STEM_BOWL, '#FFFFFFFF', 28)
            + path(GLINT, '#FFFFB547', 12)
            + path(WAVE_IN, '#FFFFB547', 18)
            + path(WAVE_OUT, '#FFFFB547', 18)
            + '    </group>\n</vector>\n')


def contrast(a, b):
    def lum(h):
        c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
        c = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


if __name__ == '__main__':
    logo = ROOT / 'logo'
    # Símbolo
    write(logo / 'glasspay-symbol-on-dark.svg', svg(symbol(WHITE, AMBER), 256, 256))
    write(logo / 'glasspay-symbol-on-light.svg', svg(symbol(INDIGO, AMBER_DEEP), 256, 256))
    write(logo / 'glasspay-symbol-black.svg', svg(symbol('#000000'), 256, 256))
    write(logo / 'glasspay-symbol-white.svg', svg(symbol(WHITE), 256, 256))
    write(logo / 'glasspay-symbol-amber.svg', svg(symbol(AMBER), 256, 256))
    # Logotipos
    for name, ink, accent in (('on-dark', WHITE, AMBER), ('on-light', INDIGO, AMBER_DEEP),
                              ('black', '#000000', '#000000'), ('white', WHITE, WHITE)):
        write(logo / f'glasspay-horizontal-{name}.svg', horizontal(ink, accent))
        write(logo / f'glasspay-stacked-{name}.svg', stacked(ink, accent))
    write(logo / 'glasspay-wordmark-on-light.svg', svg(wordmark(INDIGO), 852, 256))
    write(logo / 'glasspay-wordmark-on-dark.svg', svg(wordmark(WHITE), 852, 256))

    # Iconos de app (maestros SVG)
    icons = ROOT / 'app-icon'
    write(icons / 'appicon.svg', app_icon(1024, INDIGO, WHITE, AMBER))
    write(icons / 'appicon-tinted.svg', app_icon(1024, '#000000', WHITE, '#D9D9D9'))
    write(icons / 'brandmark.svg', app_icon(256, INDIGO, WHITE, AMBER))

    # Android: vector del icono adaptable + color de fondo
    res = REPO / 'android/app/src/main/res'
    write(res / 'drawable/ic_launcher_foreground.xml', android_foreground())
    write(res / 'values/ic_launcher_background.xml',
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{INDIGO}</color>\n</resources>\n')

    for a, b in ((AMBER, INDIGO), (AMBER_DEEP, CREAM), (INDIGO, CREAM), (WHITE, INDIGO)):
        print(f'contraste {a} sobre {b}: {contrast(a, b):.2f}:1')
