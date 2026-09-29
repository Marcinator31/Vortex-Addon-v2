# Zweite Fassung: Minecraft 1.21.11

Der 26.2-Quelltext in `src/` ist die **einzige** Quelle. Die 1.21.11-Fassung
wird bei jedem Build daraus erzeugt (`tools/port.py 1.21.11 build-port/1.21.11`)
und im selben Release mit veroeffentlicht. Neue Features landen also
automatisch in beiden Fassungen.

Was sich zwischen den Versionen unterscheidet, steht an drei Stellen:

1. `port/1.21.11/replace.tsv` -- Umbenennungen (Regex, Tab, Ersatz, optional
   Dateifilter), z. B. `GuiGraphicsExtractor` -> `GuiGraphics`,
   `mc.gui.screen()` -> `mc.screen`, Fabric-API-Namen.
2. Bedingte Bloecke direkt im Code, wenn eine Stelle anders gebaut werden muss:

       //#if 26.2
       code fuer 26.2
       //#else
       //$ code fuer 1.21.11 (auskommentiert, damit 26.2 normal baut)
       //#endif

3. `port/1.21.11/overlay/` -- ganze Dateien, die es nur in 1.21.11 gibt.

Beim Aendern von Code: laeuft der 1.21.11-Build im CI nicht durch, stehen die
Fehler als Anmerkungen mit dem Praefix `[1.21.11]` am Lauf. Die 26.2-Jar wird
trotzdem veroeffentlicht.

1.21.11 ist zur Laufzeit verschleiert: Reflection ueber Mojang-Namen
(`getDeclaredField("allMessages")`) oder Klassennamen (`getSimpleName()`)
funktioniert dort NICHT -- ueber Typen suchen oder `instanceof` benutzen.
