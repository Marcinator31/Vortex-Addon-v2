"""
Prueft Gradle-Konfigurationen gegen eine Liste bekannter Namen.

Hintergrund: 'modLocalRuntime' gibt es in dieser Loom-Fassung nicht, und der
Build bricht deshalb schon beim Einlesen ab -- vor jeder Compilierung.
Solche Namen lassen sich ohne Gradle pruefen, indem man gegen die
Konfigurationen abgleicht, die nachweislich funktionieren.
"""
import re, sys

# Standard-Gradle plus die Loom-Konfigurationen, die im Projekt nachweislich
# benutzt werden. Weitere gibt es, aber ungeprueft wird hier nichts erlaubt.
BEKANNT = {
    'implementation', 'api', 'compileOnly', 'runtimeOnly', 'testImplementation',
    'annotationProcessor', 'minecraft', 'mappings',
    'modImplementation', 'modApi', 'modCompileOnly', 'modRuntimeOnly', 'include',
}

s = open('build.gradle', encoding='utf-8').read()
# Nur im dependencies-Block suchen
m = re.search(r'dependencies\s*\{([\s\S]*?)\n\}', s)
if not m:
    print('  kein dependencies-Block gefunden'); sys.exit(0)

fehler = []
for name in re.findall(r'^\s*(\w+)[\s(]', m.group(1), flags=re.M):
    if name in ('if', 'for', 'def', 'else'): continue
    if name not in BEKANNT:
        fehler.append(name)

print(f'  dependencies-Block geprueft')
for f in sorted(set(fehler)):
    print(f'  UNBEKANNT: {f}() -- nicht in der Liste belegter Konfigurationen')
sys.exit(1 if fehler else 0)
