"""
Sucht Aufrufmuster, die vom Rest des Projekts abweichen.

Hintergrund: 'Minecraft.getInstance().setScreen(...)' war falsch, waehrend in
derselben Datei zehnmal 'Minecraft.getInstance().gui.setScreen(...)' stand.
Solche Ausreisser findet man ohne Compiler, indem man zaehlt: kommt ein
Muster genau einmal vor, waehrend eine Variante davon haeufig ist, ist das
Einzelstueck verdaechtig.
"""
import re, glob, sys, collections

muster = collections.Counter()
wo = {}
for f in glob.glob('src/**/*.java', recursive=True):
    s = open(f, encoding='utf-8').read()
    s = re.sub(r'/\*[\s\S]*?\*/', '', s)
    s = re.sub(r'^\s*//.*$', '', s, flags=re.M)
    for m in re.finditer(r'(\w+)\.getInstance\(\)((?:\.\w+)+)\(', s):
        schluessel = m.group(1) + '.getInstance()' + m.group(2)
        muster[schluessel] += 1
        wo.setdefault(schluessel, set()).add(f.split('/')[-1])

verdaechtig = []
for k, n in muster.items():
    if n > 1: continue
    letzte = k.rsplit('.', 1)[1]
    # Gibt es eine haeufige Variante, die auf dieselbe Methode endet?
    for k2, n2 in muster.items():
        if k2 != k and n2 >= 3 and k2.rsplit('.', 1)[1] == letzte:
            verdaechtig.append(f'{k}  (1x, in {", ".join(wo[k])})\n       haeufig ist: {k2}  ({n2}x)')
            break

print(f'  {len(muster)} verschiedene Aufrufmuster')
for v in verdaechtig: print('  VERDAECHTIG:', v)
if not verdaechtig: print('  keine Ausreisser')
sys.exit(1 if verdaechtig else 0)
