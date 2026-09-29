#!/usr/bin/env python3
"""
Erzeugt aus dem 26.2-Quelltext eine Fassung fuer eine andere Minecraft-Version
(z. B. 1.21.11). Der 26.2-Quelltext ist die EINZIGE Quelle -- jede Aenderung
dort landet automatisch auch in der anderen Fassung.

    python3 tools/port.py 1.21.11 build-port/1.21.11

Was passiert (Reihenfolge):
  1. Repo in den Zielordner kopieren (ohne .git, build, port/)
  2. Bedingte Bloecke aufloesen -- in .java und .json:
         //#if 26
         ... Code fuer 26.x (muss 26.2 einschliessen, ist der "echte" Code) ...
         //#elif 26.1
         //$ ... Code nur fuer 26.1.x (auskommentiert, optional) ...
         //#else
         //$ ... Code fuer alle anderen, z. B. 1.21.11 (auskommentiert) ...
         //#endif
     Bedingung = Versionen mit Komma; "26" passt auf 26, 26.1.1, 26.2 ...,
     "26.1" auf 26.1, 26.1.1, 26.1.2 ...; "1.21.11" nur auf 1.21.11.
     Es gilt der ERSTE passende Zweig. Ist das der erste, bleibt sein Code;
     sonst faellt er weg und beim gewaehlten Zweig wird "//$ " entfernt.
     Passt kein Zweig und gibt es kein //#else, faellt der ganze Block weg.
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
            neu = bloecke(text, pfad, zaehler, ziel)
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
RX_ELIF = re.compile(r'^\s*//#elif\s+(\S+)\s*$')
RX_ELSE = re.compile(r'^\s*//#else\s*$')
RX_END = re.compile(r'^\s*//#endif\s*$')
RX_ALT = re.compile(r'^(\s*)//\$ ?(.*)$')
QUELLE = '26.2'          # Version, fuer die der Quelltext geschrieben ist

def trifft(bedingung, version):
    """"26" passt auf 26.2 und 26.1.1, "26.1" auf 26.1.1, "1.21.11" nur auf sich."""
    for t in bedingung.split(','):
        t = t.strip()
        if t and (version == t or version.startswith(t + '.')):
            return True
    return False

def bloecke(text, pfad, zaehler, ziel):
    if '//#if' not in text:
        return text
    raus = []
    zweig = None            # None = ausserhalb; sonst Index des Zweigs im Block
    gewaehlt = None         # Index des Zweigs, der fuer das Ziel gilt (oder -1)
    for n, zeile in enumerate(text.split('\n'), 1):
        m_if, m_elif = RX_IF.match(zeile), RX_ELIF.match(zeile)
        if m_if:
            if zweig is not None:
                sys.exit(f'{pfad}:{n}: verschachteltes //#if')
            if not trifft(m_if.group(1), QUELLE):
                sys.exit(f'{pfad}:{n}: der erste Zweig muss {QUELLE} einschliessen')
            zweig, gewaehlt = 0, (0 if trifft(m_if.group(1), ziel) else -1)
            zaehler['bloecke'] += 1
            continue
        if m_elif or RX_ELSE.match(zeile):
            if zweig is None:
                sys.exit(f'{pfad}:{n}: //#elif/#else ohne //#if')
            if zweig == 'else':
                sys.exit(f'{pfad}:{n}: Zweig nach //#else')
            zweig = zweig + 1 if m_elif else 'else'
            if gewaehlt == -1 and (RX_ELSE.match(zeile) or trifft(m_elif.group(1), ziel)):
                gewaehlt = zweig
            continue
        if RX_END.match(zeile):
            if zweig is None:
                sys.exit(f'{pfad}:{n}: //#endif ohne //#if')
            zweig = gewaehlt = None
            continue
        if zweig is None:
            raus.append(zeile)
        elif zweig == gewaehlt:
            if zweig == 0:
                raus.append(zeile)              # echter Code bleibt
            else:
                m = RX_ALT.match(zeile)
                raus.append(m.group(1) + m.group(2) if m else zeile)
        # andere Zweige fallen weg
    if zweig is not None:
        sys.exit(f'{pfad}: //#if ohne //#endif')
    return '\n'.join(raus)


if __name__ == '__main__':
    main()
