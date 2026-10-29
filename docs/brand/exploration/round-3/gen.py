# Generador de la ronda 3 de conceptos del logo GlassPay (SVG en negro, viewBox 256).
import sys

LENS = 'M28 80H104C112 80 116 86 115 94C112 126 96 144 72 144C46 144 30 126 25 96C24 88 22 80 28 80Z'
LENS_MIRROR = 'M228 80H152C144 80 140 86 141 94C144 126 160 144 184 144C210 144 226 126 231 96C232 88 234 80 228 80Z'

def symbol_c1(ink):
    # P cuyo cuenco es una lente Wayfarer (con su reflejo) y que emite ondas: pagar mirando y hablando.
    return (f'<g transform="translate(-13 6)" fill="none" stroke="{ink}" stroke-linecap="round" stroke-linejoin="round">'
            '<path d="M70 200V64H150C166 64 174 74 172 90C168 124 150 146 116 146H70" stroke-width="28"/>'
            '<path d="M100 114L116 98" stroke-width="12"/>'
            '<path d="M192.5 47.4A92 92 0 0 1 192.5 160.6" stroke-width="18"/>'
            '<path d="M217.7 27.7A124 124 0 0 1 217.7 180.3" stroke-width="18"/></g>')

def symbol_c2(ink, paper):
    # Unos lentes: la lente derecha es el cuenco de una P.
    return (f'<g transform="translate(0 -18)">'
            f'<path fill="{ink}" d="{LENS}"/>'
            f'<path d="M48 110L66 92" stroke="{paper}" stroke-width="12" stroke-linecap="round"/>'
            f'<path d="M113 96C120 86 136 86 143 96" fill="none" stroke="{ink}" stroke-width="16" stroke-linecap="round"/>'
            f'<path d="{LENS_MIRROR}" fill="none" stroke="{ink}" stroke-width="16" stroke-linejoin="round"/>'
            f'<path d="M148 90V214" stroke="{ink}" stroke-width="18" stroke-linecap="round"/></g>')

def symbol_c3(ink, tile):
    # Sello / icono de app: C1 sobre un azulejo sólido.
    return (f'<rect x="16" y="16" width="224" height="224" rx="60" fill="{tile}"/>'
            f'<g transform="translate(128 128) scale(.64) translate(-128 -128)">{symbol_c1(ink)}</g>')

def wordmark(ink, sw=26):
    # "GlassPay" dibujado con geometría propia; la P comparte el cuenco de lente del símbolo.
    def arc(r, large, sweep, x, y): return f'A{r} {r} 0 {large} {sweep} {x:g} {y:g}'
    d = []
    cx = 80
    d.append(f'M{cx+42.4} 87.6 {arc(60,1,0,cx+60,130)} H{cx+20}')                      # G
    d.append('M184 70V190')                                                             # l
    for cx in (264, 682):                                                               # a
        d.append(f'M{cx+40} 150 {arc(40,1,1,cx+40,149.9)} M{cx+40} 110V190')
    for cx in (369, 457):                                                               # s s
        d.append(f'M{cx+21.7} 112.5 {arc(25,1,0,cx,150)} {arc(25,1,1,cx-21.7,187.5)}')
    x = 528                                                                             # P
    d.append(f'M{x} 190V70H{x+50}C{x+66} 70 {x+74} 80 {x+72} 96C{x+68} 128 {x+52} 146 {x+26} 146H{x}')
    cx = 794                                                                            # y
    d.append(f'M{cx-34} 110L{cx} 190M{cx+34} 110L{cx-19.5} 236')
    return (f'<path fill="none" stroke="{ink}" stroke-width="{sw}" stroke-linecap="round" '
            f'stroke-linejoin="round" d="{" ".join(d)}"/>')

def svg(body, w=256, h=256, bg=None):
    back = f'<rect width="{w}" height="{h}" fill="{bg}"/>' if bg else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}">{back}{body}</svg>\n'

def lockup(symbol, ink):
    return symbol + f'<g transform="translate(262 34) scale(.8)">{wordmark(ink)}</g>'

if __name__ == '__main__':
    open('c1-p-lente.svg', 'w').write(svg(symbol_c1('#000')))
    open('c2-gafas-p.svg', 'w').write(svg(symbol_c2('#000', '#fff')))
    open('c3-sello.svg', 'w').write(svg(symbol_c3('#fff', '#000')))
    open('wordmark.svg', 'w').write(svg(wordmark('#000'), 852, 256))
    open('c1-lockup.svg', 'w').write(svg(lockup(symbol_c1('#000'), '#000'), 960, 256))
    open('c2-lockup.svg', 'w').write(svg(lockup(symbol_c2('#000', '#fff'), '#000'), 960, 256))
    open('c3-lockup.svg', 'w').write(svg(lockup(symbol_c3('#fff', '#000'), '#000'), 960, 256))

# --- Tablero de color ---
PALETTES = [
    ('Confianza', '#0B1F4B', '#3D7BFF', '#EAF2FF', '#FFFFFF'),   # nombre, fondo, acento, claro, tinta sobre fondo
    ('Menta cristal', '#0A0D14', '#3EF2C2', '#EFFFFA', '#FFFFFF'),
    ('Ámbar noche', '#141A38', '#FFB547', '#FFF6E8', '#FFFFFF'),
]

def symbol_c1_two_tone(ink, accent):
    s = symbol_c1(ink)
    return s.replace('stroke-width="18"/>', f'stroke-width="18" stroke="{accent}"/>').replace(
        '<path d="M100 114L116 98" stroke-width="12"/>', f'<path d="M100 114L116 98" stroke-width="12" stroke="{accent}"/>')

def color_board():
    W, H, row = 1800, 1040, 320
    out = [f'<rect width="{W}" height="{H}" fill="#F6F6F3"/>',
           '<text x="60" y="70" font-family="-apple-system,Helvetica" font-size="34" font-weight="700" fill="#111">GlassPay — paletas propuestas (C1 P-Lente)</text>']
    for i, (name, bg, accent, light, ink) in enumerate(PALETTES):
        y = 110 + i * row
        out.append(f'<text x="60" y="{y+20}" font-family="-apple-system,Helvetica" font-size="24" font-weight="600" fill="#111">{i+1}. {name}</text>')
        # icono de app
        out.append(f'<g transform="translate(60 {y+40})"><rect width="240" height="240" rx="54" fill="{bg}"/>'
                   f'<g transform="translate(120 120) scale(.66) translate(-128 -128)">{symbol_c1_two_tone(ink, accent)}</g></g>')
        # lockup sobre fondo oscuro
        out.append(f'<g transform="translate(340 {y+40})"><rect width="620" height="240" rx="24" fill="{bg}"/>'
                   f'<g transform="translate(40 50) scale(.56)">{symbol_c1_two_tone(ink, accent)}'
                   f'<g transform="translate(262 34) scale(.8)">{wordmark(ink)}</g></g></g>')
        # lockup sobre claro
        out.append(f'<g transform="translate(1000 {y+40})"><rect width="620" height="240" rx="24" fill="{light}"/>'
                   f'<g transform="translate(40 50) scale(.56)">{symbol_c1_two_tone(bg, accent if name != "Ámbar noche" else "#E08A00")}'
                   f'<g transform="translate(262 34) scale(.8)">{wordmark(bg)}</g></g></g>')
        # muestras
        for j, (c, label) in enumerate(((bg, bg), (accent, accent), (light, light))):
            out.append(f'<rect x="1650" y="{y+50+j*72}" width="56" height="56" rx="12" fill="{c}" stroke="#0002"/>'
                       f'<text x="1650" y="{y+122+j*72}" font-family="Menlo" font-size="13" fill="#333">{label}</text>')
    return svg(''.join(out), W, H)

if __name__ == '__main__':
    open('palettes.svg', 'w').write(color_board())
