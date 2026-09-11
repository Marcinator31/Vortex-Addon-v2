"""
Prueft, ob der Workflow dieselbe Java-Version installiert, die das Projekt braucht.

Dieser Fehler ist jetzt dreimal aufgetreten: build.gradle verlangt Java 25,
der Workflow installiert 21, und der Build bricht mit
"release version 25 not supported" ab. Die Ursache ist immer dieselbe --
eine Workflow-Datei, die aus einem aelteren Projekt mitkopiert wurde.
"""
import re, glob, sys, os

g = open('build.gradle', encoding='utf-8').read()
m = re.search(r'VERSION_(\d+)', g)
if not m:
    print('  build.gradle: keine VERSION_x gefunden'); sys.exit(0)
noetig = m.group(1)

fehler = []
for f in glob.glob('.github/workflows/*.yml'):
    s = open(f, encoding='utf-8').read()
    for v in re.findall(r"java-version: '(\d+)'", s):
        if v != noetig:
            fehler.append(f'{os.path.basename(f)}: installiert JDK {v}, gebraucht wird {noetig}')

fm = glob.glob('src/*/resources/fabric.mod.json')
if fm:
    import json
    dep = json.load(open(fm[0])).get('depends', {}).get('java', '')
    n = re.search(r'(\d+)', str(dep))
    if n and n.group(1) != noetig:
        fehler.append(f'fabric.mod.json: verlangt Java {n.group(1)}, build.gradle {noetig}')

print(f'  build.gradle verlangt Java {noetig}')
for x in fehler: print('  FEHLER:', x)
if not fehler: print('  Workflow und fabric.mod.json passen dazu')
sys.exit(1 if fehler else 0)
