# Generador de la ronda 4 del logo GlassPay: conceptos centrados en los lentes (viewBox 256).
import sys
sys.path.insert(0, '../round-3')
from gen import wordmark, svg  # wordmark dibujado a mano en la ronda 3

LENS_L = 'M28 96H104C112 96 116 102 115 110C112 142 96 160 72 160C46 160 30 142 25 112C24 104 22 96 28 96Z'
LENS_R = 'M228 96H152C144 96 140 102 141 110C144 142 160 160 184 160C210 160 226 142 231 112C232 104 234 96 228 96Z'
BRIDGE = 'M113 112C120 102 136 102 143 112'

def glasses(ink, paper, glint_right=True):
    g = (f'<path fill="{ink}" d="{LENS_L}"/><path fill="{ink}" d="{LENS_R}"/>'
         f'<path d="{BRIDGE}" fill="none" stroke="{ink}" stroke-width="14" stroke-linecap="round"/>'
         f'<path d="M48 126L66 108" stroke="{paper}" stroke-width="12" stroke-linecap="round"/>')
    if glint_right:
        g += f'<path d="M164 126L182 108" stroke="{paper}" stroke-width="12" stroke-linecap="round"/>'
    return g

def a_lentes_que_hablan(ink, paper, accent=None):
    # Los lentes "hablan": las ondas salen de la patilla, donde los lentes inteligentes tienen la bocina.
    accent = accent or ink
    return (f'<g transform="translate(6 26) scale(.78)">{glasses(ink, paper)}'
            f'<path d="M228 98H244" stroke="{ink}" stroke-width="14" stroke-linecap="round"/>'
            f'<path d="M22 98H12" stroke="{ink}" stroke-width="14" stroke-linecap="round"/></g>'
            f'<g fill="none" stroke="{accent}" stroke-width="14" stroke-linecap="round">'
            f'<path d="M213 87.8A30 30 0 0 1 213 130.2"/><path d="M228.6 72.2A52 52 0 0 1 228.6 145.8"/></g>')

def b_gp_lentes(ink, accent=None):
    # Monograma: la G y la P son las dos lentes y el puente une sus trazos superiores.
    accent = accent or ink
    return (f'<g transform="translate(0 -12)" fill="none" stroke="{ink}" stroke-width="22" stroke-linecap="round" stroke-linejoin="round">'
            '<path d="M94 76H58A34 34 0 0 0 24 110V130A34 34 0 0 0 58 164H82A34 34 0 0 0 116 130V118H88"/>'
            f'<path d="M94 76C112 66 132 66 150 76" stroke="{accent}"/>'
            '<path d="M140 220V110A34 34 0 0 1 174 76H198A34 34 0 0 1 232 110V130A34 34 0 0 1 198 164H140"/></g>')

def c_hud(ink, paper, accent=None):
    # Lo que ves en tu lente: la lente derecha muestra el cobro (signo de pesos), como una pantalla.
    accent = accent or paper
    s = 'M192.7 111A10 10 0 1 0 184 126A10 10 0 1 1 175.3 141'
    return (f'<g transform="translate(0 -4)">{glasses(ink, paper, glint_right=False)}'
            f'<g fill="none" stroke="{accent}" stroke-width="7" stroke-linecap="round">'
            f'<path d="{s}"/><path d="M184 104V148"/></g></g>')

def d_marco(ink, accent=None):
    # Solo el marco, en línea: ligero como el cristal; el puente es una onda de voz.
    accent = accent or ink
    return (f'<g transform="translate(0 -4)" fill="none" stroke="{ink}" stroke-width="16" stroke-linejoin="round" stroke-linecap="round">'
            f'<path d="{LENS_L}"/><path d="{LENS_R}"/>'
            f'<path d="M113 106Q120.5 96 128 106T143 106" stroke="{accent}"/>'
            f'<path d="M20 100H12M236 100H244"/></g>')

def lockup(symbol, ink):
    return symbol + f'<g transform="translate(262 34) scale(.8)">{wordmark(ink)}</g>'

if __name__ == '__main__':
    concepts = {
        'a-lentes-que-hablan': a_lentes_que_hablan('#000', '#fff'),
        'b-gp-lentes': b_gp_lentes('#000'),
        'c-hud': c_hud('#000', '#fff'),
        'd-marco': d_marco('#000'),
    }
    for name, body in concepts.items():
        open(f'{name}.svg', 'w').write(svg(body))
        open(f'{name}-lockup.svg', 'w').write(svg(lockup(body, '#000'), 960, 256))
    # Muestra de color con la paleta "Confianza" (fondo marino, acento azul).
    NAVY, BLUE, ICE = '#0B1F4B', '#3D7BFF', '#EAF2FF'
    color = [(a_lentes_que_hablan('#fff', NAVY, BLUE), .92), (b_gp_lentes('#fff', BLUE), .78), (c_hud('#fff', NAVY, BLUE), .9), (d_marco('#fff', BLUE), .9)]
    tiles = ''.join(f'<g transform="translate({40 + i*300} 40)"><rect width="260" height="260" rx="58" fill="{NAVY}"/>'
                    f'<g transform="translate(130 130) scale({k}) translate(-128 -128)">{b}</g></g>' for i, (b, k) in enumerate(color))
    open('color-hint.svg', 'w').write(svg(f'<rect width="1240" height="340" fill="{ICE}"/>' + tiles, 1240, 340))
