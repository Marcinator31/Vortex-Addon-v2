#!/usr/bin/env python3
"""
Erzeugt aus dem 26.2-Quelltext eine Fassung fuer eine andere Minecraft-Version
(z. B. 1.21.11). Der 26.2-Quelltext ist die EINZIGE Quelle -- jede Aenderung
dort landet automatisch auch in der anderen Fassung.

    python3 tools/port.py 1.21.11 build-port/1.21.11

Was passiert (Reihenfolge):
  1. Repo in den Zielordner kopieren (ohne .git, build, port/)
  2. Bedingte Bloecke aufloesen -- in .java und .json:
         //#if 26.2
         ... Code nur fuer 26.2 ...
         //#else
         //$ ... Code nur fuer die andere Fassung (auskommentiert) ...
         //#endif
     Fuer 26.2 bleibt die Datei, wie sie ist (der else-Teil ist Kommentar).
     Fuer die andere Fassung faellt der if-Teil weg und "//$ " wird entfernt.
  3. Ersetzungen aus port/<ziel>/replace.tsv (Regex <TAB> Ersatz [<TAB> Dateien]),
     fuer .java; dritte Spalte optional: "A.java,B.java" nur dort, "!A.java" ausser dort;
     Zeilen mit "json:" vor dem Regex gelten nur fuer .json-Dateien
  4. Dateien aus port/<ziel>/overlay/ ueber den Baum legen (ganze Dateien)
  5. Dateien aus port/<ziel>/delete.txt entfernen (eine pro Zeile)
  6. gradle.properties: Werte aus port/<ziel>/gradle.properties ueberschreiben;
     build.gradle / settings.gradle aus port/<ziel>/ ersetzen, falls vorhanden
"""
import os, re, shutil, sys

def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    ziel, out = sys.argv[1], sys.argv[2]
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pdir = os.path.join(repo, 'port', ziel)
    if not os.path.isdir(pdir):
        sys.exit(f'port/{ziel} fehlt')
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(repo, out, ignore=shutil.ignore_patterns('.git', 'build', '.gradle', 'port', 'build-port', 'run'))

    ersetzen = []
    rp = os.path.join(pdir, 'replace.tsv')
    if os.path.exists(rp):
        for n, zeile in enumerate(open(rp, encoding='utf-8'), 1):
            zeile = zeile.rstrip('\n')
            if not zeile.strip() or zeile.startswith('#'):
                continue
            teile = zeile.split('\t')
            if len(teile) not in (2, 3):
                sys.exit(f'replace.tsv Zeile {n}: Regex<TAB>Ersatz[<TAB>Dateifilter] erwartet')
            art = 'java'
            if teile[0].startswith('json:'):
                art, teile[0] = 'json', teile[0][5:]
            filt = [f.strip() for f in teile[2].split(',')] if len(teile) == 3 else []
            ersetzen.append((art, re.compile(teile[0]), teile[1].replace('\\t', '\t'), filt))

    zaehler = {'dateien': 0, 'bloecke': 0}
    for wurzel, _, dateien in os.walk(os.path.join(out, 'src')):
        for d in dateien:
            if not d.endswith(('.java', '.json')):
                continue
            pfad = os.path.join(wurzel, d)
            text = open(pfad, encoding='utf-8').read()
            neu = bloecke(text, pfad, zaehler)
            endung = 'java' if d.endswith('.java') else 'json'
            for art, rx, rep, filt in ersetzen:
                if art == endung and passt(d, filt):
                    neu = rx.sub(rep, neu)
            if neu != text:
                open(pfad, 'w', encoding='utf-8').write(neu)
                zaehler['dateien'] += 1

    ov = os.path.join(pdir, 'overlay')
    if os.path.isdir(ov):
        for wurzel, _, dateien in os.walk(ov):
            for d in dateien:
                q = os.path.join(wurzel, d)
                z = os.path.join(out, os.path.relpath(q, ov))
                os.makedirs(os.path.dirname(z), exist_ok=True)
                shutil.copy2(q, z)

    dl = os.path.join(pdir, 'delete.txt')
    if os.path.exists(dl):
        for zeile in open(dl, encoding='utf-8'):
            z = zeile.strip()
            if z and not z.startswith('#'):
                p = os.path.join(out, z)
                if os.path.exists(p):
                    os.remove(p)

    for name in ('build.gradle', 'settings.gradle'):
        q = os.path.join(pdir, name)
        if os.path.exists(q):
            shutil.copy2(q, os.path.join(out, name))
    gp = os.path.join(pdir, 'gradle.properties')
    if os.path.exists(gp):
        werte = {}
        for zeile in open(gp, encoding='utf-8'):
            if '=' in zeile and not zeile.lstrip().startswith('#'):
                k, v = zeile.split('=', 1)
                werte[k.strip()] = v.strip()
        zp = os.path.join(out, 'gradle.properties')
        zeilen = open(zp, encoding='utf-8').read().split('\n')
        gesehen = set()
        for i, zeile in enumerate(zeilen):
            if '=' in zeile and not zeile.lstrip().startswith('#'):
                k = zeile.split('=', 1)[0].strip()
                if k in werte:
                    zeilen[i] = f'{k}={werte[k]}'
                    gesehen.add(k)
        for k, v in werte.items():
            if k not in gesehen:
                zeilen.append(f'{k}={v}')
        # mod_version: "+26.2" gegen die Zielversion tauschen
        for i, zeile in enumerate(zeilen):
            if zeile.startswith('mod_version=') and 'mod_version' not in werte:
                zeilen[i] = re.sub(r'\+[\d.]+$', '+' + ziel, zeile)
        open(zp, 'w', encoding='utf-8').write('\n'.join(zeilen))
    print(f'Port {ziel}: {zaehler["dateien"]} Dateien angepasst, {zaehler["bloecke"]} bedingte Bloecke -> {out}')


def passt(datei, filt):
    """Dateifilter: "A.java,B.java" = nur diese; "!A.java" = alle ausser diesen."""
    if not filt:
        return True
    rein = [f for f in filt if not f.startswith('!')]
    raus = [f[1:] for f in filt if f.startswith('!')]
    if datei in raus:
        return False
    return not rein or datei in rein


RX_IF = re.compile(r'^\s*//#if\s+(\S+)\s*$')
RX_ELSE = re.compile(r'^\s*//#else\s*$')
RX_END = re.compile(r'^\s*//#endif\s*$')
RX_ALT = re.compile(r'^(\s*)//\$ ?(.*)$')

def bloecke(text, pfad, zaehler):
    if '//#if' not in text:
        return text
    raus = []
    zustand = None          # None | 'if' | 'else'
    for n, zeile in enumerate(text.split('\n'), 1):
        if RX_IF.match(zeile):
            if zustand:
                sys.exit(f'{pfad}:{n}: verschachteltes //#if')
            zustand = 'if'
            zaehler['bloecke'] += 1
            continue
        if RX_ELSE.match(zeile):
            if zustand != 'if':
                sys.exit(f'{pfad}:{n}: //#else ohne //#if')
            zustand = 'else'
            continue
        if RX_END.match(zeile):
            if not zustand:
                sys.exit(f'{pfad}:{n}: //#endif ohne //#if')
            zustand = None
            continue
        if zustand == 'if':
            continue                       # 26.2-Code faellt weg
        if zustand == 'else':
            m = RX_ALT.match(zeile)
            raus.append(m.group(1) + m.group(2) if m else zeile)
            continue
        raus.append(zeile)
    if zustand:
        sys.exit(f'{pfad}: //#if ohne //#endif')
    return '\n'.join(raus)


if __name__ == '__main__':
    main()
