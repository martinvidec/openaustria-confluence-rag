# Spec 03: XHTML → Plaintext Konverter

**Issues:** #4, #5
**Phase:** 2
**Abhängigkeiten:** #3

---

## Ziel

Konvertierung des Confluence XHTML Storage Formats in sauberen, lesbaren Plaintext. Spezialbehandlung für PlantUML-Makros.

## Package

`at.openaustria.confluencerag.crawler.converter`

---

## Confluence Storage Format

Confluence speichert Seiteninhalte als XHTML ("Storage Format"). Beispiel:

```xml
<p>Normaler Text mit <strong>Fettdruck</strong> und <em>Kursiv</em>.</p>

<h2>Überschrift</h2>

<ul>
  <li>Punkt 1</li>
  <li>Punkt 2
    <ul>
      <li>Unterpunkt</li>
    </ul>
  </li>
</ul>

<table>
  <tr><th>Header 1</th><th>Header 2</th></tr>
  <tr><td>Wert 1</td><td>Wert 2</td></tr>
</table>

<ac:structured-macro ac:name="code">
  <ac:parameter ac:name="language">java</ac:parameter>
  <ac:plain-text-body><![CDATA[public class Foo {}]]></ac:plain-text-body>
</ac:structured-macro>

<ac:structured-macro ac:name="info">
  <ac:rich-text-body>
    <p>Wichtiger Hinweis!</p>
  </ac:rich-text-body>
</ac:structured-macro>
```

---

## Klassen

### ConfluenceHtmlConverter

```java
@Component
public class ConfluenceHtmlConverter {

    private final List<MacroHandler> macroHandlers;

    /**
     * Konvertiert Confluence Storage Format XHTML in Plaintext.
     * @param storageFormat der XHTML-String aus body.storage.value
     * @return sauberer Plaintext
     */
    public String convert(String storageFormat);
}
```

### MacroHandler (Strategy Pattern)

```java
public interface MacroHandler {
    /** Macro-Name den dieser Handler verarbeitet */
    String getMacroName();

    /** Konvertiert das Makro-Element in Plaintext */
    String handle(Element macroElement);
}
```

---

## Issue #4 — Konvertierungsregeln (Standard-Elemente & Makros)

### HTML-Elemente

| Element | Konvertierung | Beispiel Output |
|---|---|---|
| `<p>` | Text + Leerzeile | `Text\n\n` |
| `<h1>`–`<h6>` | `# `–`###### ` Prefix (Markdown-Stil) | `## Überschrift\n\n` |
| `<strong>`, `<b>` | Text ohne Markup | `Fettdruck` |
| `<em>`, `<i>` | Text ohne Markup | `Kursiv` |
| `<br>` | Zeilenumbruch | `\n` |
| `<a href="...">` | `Text (URL)` | `Confluence (http://...)` |
| `<ul>/<ol> > <li>` | `- ` bzw. `1. ` mit Einrückung | `- Punkt 1\n  - Unterpunkt` |
| `<table>` | Zeilenweise, Pipe-getrennt | `Header 1 | Header 2\nWert 1 | Wert 2` |
| `<img>` | Ignorieren (Bilder nicht relevant für RAG) | — |
| `<ac:image>` | Ignorieren | — |
| `<ac:link>` | Link-Text extrahieren | `Seitentitel` |

### Standard-Makros

| Makro (`ac:name`) | Konvertierung |
|---|---|
| `code` | ` ```{language}\n{code}\n``` ` |
| `info` | `Info: {body}` |
| `note` | `Hinweis: {body}` |
| `warning` | `Warnung: {body}` |
| `tip` | `Tipp: {body}` |
| `panel` | `{title}:\n{body}` (Titel optional) |
| `expand` | `{title}:\n{body}` (Expander, Inhalt immer extrahieren) |
| `excerpt` | `{body}` (transparent, Inhalt extrahieren) |
| `toc` | Ignorieren (Table of Contents, kein Textinhalt) |
| `children` | Ignorieren (Navigation-Makro) |
| `include` | Ignorieren (referenziert andere Seite — wird separat gecrawlt) |
| `jira` | `JIRA: {key}` falls Key vorhanden, sonst ignorieren |
| `status` | `[{text}]` (Status-Badge Text) |
| `noformat` | `{body}` (Plaintext beibehalten) |
| Unbekannte Makros | `ac:rich-text-body` oder `ac:plain-text-body` extrahieren, falls vorhanden. Sonst ignorieren. |

### Makro-Struktur im Storage Format

```xml
<!-- Makro mit Rich-Text Body -->
<ac:structured-macro ac:name="info">
  <ac:parameter ac:name="title">Optionaler Titel</ac:parameter>
  <ac:rich-text-body>
    <p>HTML-Inhalt</p>
  </ac:rich-text-body>
</ac:structured-macro>

<!-- Makro mit Plain-Text Body -->
<ac:structured-macro ac:name="code">
  <ac:parameter ac:name="language">java</ac:parameter>
  <ac:plain-text-body><![CDATA[Plaintext-Inhalt]]></ac:plain-text-body>
</ac:structured-macro>

<!-- Makro ohne Body -->
<ac:structured-macro ac:name="toc">
  <ac:parameter ac:name="maxLevel">3</ac:parameter>
</ac:structured-macro>
```

### Jsoup-Konfiguration

```java
// Confluence Storage Format nutzt Custom-Namespaces (ac:, ri:)
// Jsoup XML-Parser verwenden statt HTML-Parser:
Document doc = Jsoup.parse(storageFormat, "", Parser.xmlParser());
```

**Wichtig:** Der XML-Parser ist nötig, damit `ac:structured-macro`, `ac:parameter` etc. als Elemente erkannt werden. Der HTML-Parser würde die Namespaced Tags nicht korrekt parsen.

---

## Issue #5 — PlantUML-Makro Extraktion

### PlantUML im Storage Format

Es gibt verschiedene Confluence-Plugins für PlantUML. Die zwei häufigsten Formate:

#### Format 1: PlantUML Plugin (häufigstes Format)

```xml
<ac:structured-macro ac:name="plantuml">
  <ac:plain-text-body><![CDATA[
@startuml
Alice -> Bob: Hello
Bob -> Alice: Hi!
@enduml
  ]]></ac:plain-text-body>
</ac:structured-macro>
```

#### Format 2: PlantUML for Confluence (Cloud-kompatibel)

```xml
<ac:structured-macro ac:name="plantuml-cloud" ac:macro-id="...">
  <ac:plain-text-body><![CDATA[
@startuml
class User {
  +name: String
  +email: String
}
@enduml
  ]]></ac:plain-text-body>
</ac:structured-macro>
```

#### Format 3: PlantUML als Attachment-Referenz

```xml
<ac:structured-macro ac:name="plantuml">
  <ac:parameter ac:name="attachment">diagram.puml</ac:parameter>
</ac:structured-macro>
```

### PlantUmlMacroHandler

```java
@Component
public class PlantUmlMacroHandler implements MacroHandler {

    @Override
    public String getMacroName() {
        return "plantuml";  // Auch "plantuml-cloud" matchen
    }

    @Override
    public String handle(Element macroElement) {
        // 1. Prüfen ob plain-text-body vorhanden
        Element body = macroElement.selectFirst("ac|plain-text-body");
        if (body != null) {
            String umlSource = body.text().trim();
            return "\n```plantuml\n" + umlSource + "\n```\n";
        }

        // 2. Prüfen ob Attachment-Referenz
        Element attachParam = macroElement.selectFirst(
            "ac|parameter[ac:name=attachment]"
        );
        if (attachParam != null) {
            return "\n[PlantUML-Diagramm: " + attachParam.text() + "]\n";
        }

        // 3. Fallback: Makro ignorieren
        return "";
    }
}
```

### Matching-Logik

Alle Makros deren Name mit `plantuml` beginnt sollen durch den PlantUML-Handler verarbeitet werden:

```java
// Im ConfluenceHtmlConverter:
MacroHandler handler = macroHandlers.stream()
    .filter(h -> macroName.startsWith(h.getMacroName()))
    .findFirst()
    .orElse(defaultMacroHandler);
```

---

## Konvertierungsalgorithmus

```
1. Parse XHTML mit Jsoup XML-Parser
2. Traversiere den DOM-Baum (depth-first)
3. Für jedes Element:
   a. ac:structured-macro → passenden MacroHandler aufrufen
   b. HTML-Element → gemäß Konvertierungsregeln in Text umwandeln
   c. Text-Nodes → Text übernehmen
4. Überflüssige Leerzeilen zusammenführen (max. 2 aufeinanderfolgende)
5. Leading/Trailing Whitespace trimmen
```

---

## Akzeptanzkriterien

### Issue #4

- [ ] Normaler Text (p, strong, em, br) wird korrekt konvertiert
- [ ] Überschriften werden als Markdown-Headings dargestellt
- [ ] Listen werden mit Einrückung dargestellt
- [ ] Tabellen werden zeilenweise mit Pipe-Trennung dargestellt
- [ ] Code-Makros werden als Codeblöcke dargestellt
- [ ] Info/Note/Warning/Tip-Makros werden mit Prefix extrahiert
- [ ] Panel/Expand-Makros werden mit Titel extrahiert
- [ ] Unbekannte Makros: Body-Content wird extrahiert oder ignoriert
- [ ] Bilder und Layout-Makros werden ignoriert
- [ ] Unit-Tests mit realistischen Confluence-XHTML-Samples

### Issue #5

- [ ] PlantUML-Quellcode wird als Codeblock extrahiert (Format 1 + 2)
- [ ] Attachment-Referenz-Variante wird als Platzhalter dargestellt (Format 3)
- [ ] Makro ohne Body wird ignoriert
- [ ] Unit-Tests für alle drei PlantUML-Formate
