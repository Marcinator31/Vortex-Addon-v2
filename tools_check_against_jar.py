"""
Prueft den Addon-Quelltext gegen die Client-Jar in libs/.

Findet zwei Fehlerarten, die uns je einen Build gekostet haben:

 1. VERALTETE JAR: Das Addon benutzt Category.BOTS, die Jar stammt aber von
    einer aelteren Client-Fassung ohne diese Kategorie.
 2. GERATENE METHODEN: Aufrufe auf Minecraft-Klassen, die es dort nicht gibt
    (getArmorSlots). Dagegen hilft: nur Aufrufe verwenden, die auch im
    Client-Quelltext oder in der Jar vorkommen.
"""
import re, glob, os, sys, zipfile, struct

JAR = 'libs/vortexclient.jar'
if not os.path.exists(JAR):
    print('  libs/vortexclient.jar fehlt -- siehe libs/LIESMICH.txt')
    sys.exit(1)

z = zipfile.ZipFile(JAR)

def pool(name):
    d = z.read(name)
    count = struct.unpack('>H', d[8:10])[0]
    i, k, out = 10, 1, []
    while k < count:
        t = d[i]
        if t == 1:
            ln = struct.unpack('>H', d[i+1:i+3])[0]
            out.append(d[i+3:i+3+ln].decode('utf-8', 'replace')); i += 3 + ln
        elif t in (5, 6): i += 9; k += 1
        elif t in (7, 8, 16, 19, 20): i += 3
        elif t == 15: i += 4
        else: i += 5
        k += 1
    return out

quelle = '\n'.join(open(f, encoding='utf-8').read()
                   for f in glob.glob('src/**/*.java', recursive=True))
fehler = []

# 1) Benutzte Category-Werte muessen in der Jar stehen
kat = 'com/vortex/client/module/Module$Category.class'
if kat in z.namelist():
    vorhanden = set(pool(kat))
    for wert in set(re.findall(r'Category\.([A-Z_]+)', quelle)):
        if wert not in vorhanden:
            fehler.append(f'Category.{wert} gibt es in der Client-Jar nicht '
                          f'-- ist sie veraltet? (Jar kennt: '
                          f'{", ".join(sorted(x for x in vorhanden if x.isupper() and x.isalpha() and 2 < len(x) < 14))})')

# 2) Benutzte Client-Klassen muessen in der Jar sein
inJar = set(x[:-6].replace('/', '.') for x in z.namelist() if x.endswith('.class'))
eigene = set(f[len('src/client/java/'):-5].replace('/', '.')
             for f in glob.glob('src/client/java/**/*.java', recursive=True))
for ref in set(re.findall(r'com\.vortex\.client\.[\w.]*\.[A-Z]\w+', quelle)):
    # Felder wie ModuleManager.INSTANCE sehen aus wie Klassennamen. Wenn der
    # Teil davor schon eine Klasse ist, war es ein Feldzugriff.
    if ref.rsplit('.', 1)[0] in inJar:
        continue
    if ref not in inJar and ref not in eigene:
        fehler.append(f'{ref} fehlt in der Client-Jar')

print(f'  Addon gegen {os.path.basename(JAR)} geprueft')
for x in sorted(set(fehler)): print('  FEHLT:', x)
sys.exit(1 if fehler else 0)
