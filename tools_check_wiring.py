"""
Prueft, ob nach dem Umzug alle Verbindungen noch stehen.

Beim Verschieben der Module ins Addon sind mehrfach Verbindungen gerissen,
ohne dass der Compiler etwas gemerkt hat:
  - EspModule und BlockEspModule verloren ihren Auswahlbildschirm
  - Freecam.toggle() wurde von niemandem mehr gerufen
  - registerSafety lief einmal doppelt, einmal gar nicht

Diese Fehler haben eines gemeinsam: Der Code uebersetzt sauber, tut aber
nichts. Genau dagegen ist diese Pruefung.
"""
import glob, os, re, sys

alle = '\n'.join(open(f, encoding='utf-8').read()
                 for f in glob.glob('src/**/*.java', recursive=True))
fehler = []

# 1) Jeder Bildschirm braucht jemanden, der ihn oeffnet
for scr in glob.glob('src/**/gui/*Screen.java', recursive=True):
    n = os.path.basename(scr)[:-5]
    # Abstrakte Basisklassen werden nie direkt erzeugt -- kein Fehler.
    if re.search(r'abstract\s+class\s+' + n + r'\b', open(scr, encoding='utf-8').read()):
        continue
    if not re.search(r'new (?:[\w.]*\.)?' + n + r'\s*\(', alle):
        fehler.append(f'{n} wird nirgends geoeffnet')

# 2) Module mit eigener Liste muessen ExtraData umsetzen, sonst wird sie
#    nicht gespeichert
for f in glob.glob('src/**/module/modules/*.java', recursive=True):
    s = open(f, encoding='utf-8').read()
    n = os.path.basename(f)[:-5]
    if re.search(r'public\s+String\s+serialize\w*\s*\(', s) and 'ExtraData' not in s:
        fehler.append(f'{n} hat eine Liste, setzt aber ExtraData nicht um')

# 3) Renderer mit register() muessen angemeldet werden
for f in glob.glob('src/**/hud/*.java', recursive=True):
    n = os.path.basename(f)[:-5]
    s = open(f, encoding='utf-8').read()
    if re.search(r'public\s+static\s+void\s+register\s*\(\s*\)', s):
        if not re.search(r'\b' + n + r'\.register\s*\(', alle):
            fehler.append(f'{n}.register() wird nicht aufgerufen -- der Renderer zeichnet nichts')

print(f'  Verdrahtung geprueft')
for x in sorted(set(fehler)): print('  FEHLT:', x)
sys.exit(1 if fehler else 0)
