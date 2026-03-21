package at.openaustria.confluencerag.crawler.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceHtmlConverterTest {

    private ConfluenceHtmlConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ConfluenceHtmlConverter(List.of(
                new CodeMacroHandler(),
                new PanelMacroHandler(),
                new ExpandMacroHandler(),
                new NoFormatMacroHandler(),
                new PlantUmlMacroHandler()
        ));
    }

    @Test
    void convertsSimpleText() {
        String html = "<p>Hello World</p>";
        String result = converter.convert(html);
        assertEquals("Hello World", result);
    }

    @Test
    void convertsHeadings() {
        String html = "<h1>Title</h1><h2>Subtitle</h2><h3>Section</h3>";
        String result = converter.convert(html);
        assertTrue(result.contains("# Title"));
        assertTrue(result.contains("## Subtitle"));
        assertTrue(result.contains("### Section"));
    }

    @Test
    void convertsBoldAndItalic() {
        String html = "<p>This is <strong>bold</strong> and <em>italic</em> text.</p>";
        String result = converter.convert(html);
        assertTrue(result.contains("bold"));
        assertTrue(result.contains("italic"));
        assertFalse(result.contains("<strong>"));
        assertFalse(result.contains("<em>"));
    }

    @Test
    void convertsUnorderedList() {
        String html = "<ul><li>Item 1</li><li>Item 2</li></ul>";
        String result = converter.convert(html);
        assertTrue(result.contains("- Item 1"));
        assertTrue(result.contains("- Item 2"));
    }

    @Test
    void convertsOrderedList() {
        String html = "<ol><li>First</li><li>Second</li></ol>";
        String result = converter.convert(html);
        assertTrue(result.contains("1. First"));
        assertTrue(result.contains("2. Second"));
    }

    @Test
    void convertsNestedList() {
        String html = "<ul><li>Parent<ul><li>Child</li></ul></li></ul>";
        String result = converter.convert(html);
        assertTrue(result.contains("- Parent"));
        assertTrue(result.contains("  - Child"));
    }

    @Test
    void convertsTable() {
        String html = "<table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1</td></tr></table>";
        String result = converter.convert(html);
        assertTrue(result.contains("Name | Value"));
        assertTrue(result.contains("A | 1"));
    }

    @Test
    void convertsLinks() {
        String html = "<p>Visit <a href=\"https://example.com\">Example</a> for details.</p>";
        String result = converter.convert(html);
        assertTrue(result.contains("Example (https://example.com)"));
    }

    @Test
    void convertsCodeMacro() {
        String html = """
            <ac:structured-macro ac:name="code">
              <ac:parameter ac:name="language">java</ac:parameter>
              <ac:plain-text-body><![CDATA[public class Foo {}]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("```java"));
        assertTrue(result.contains("public class Foo {}"));
        assertTrue(result.contains("```"));
    }

    @Test
    void convertsInfoMacro() {
        String html = """
            <ac:structured-macro ac:name="info">
              <ac:rich-text-body><p>Important notice!</p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("Info: Important notice!"));
    }

    @Test
    void convertsWarningMacro() {
        String html = """
            <ac:structured-macro ac:name="warning">
              <ac:parameter ac:name="title">Danger</ac:parameter>
              <ac:rich-text-body><p>Be careful!</p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("Warnung (Danger): Be careful!"));
    }

    @Test
    void convertsPanelMacro() {
        String html = """
            <ac:structured-macro ac:name="panel">
              <ac:parameter ac:name="title">My Panel</ac:parameter>
              <ac:rich-text-body><p>Panel content</p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("My Panel:"));
        assertTrue(result.contains("Panel content"));
    }

    @Test
    void convertsExpandMacro() {
        String html = """
            <ac:structured-macro ac:name="expand">
              <ac:parameter ac:name="title">Click to expand</ac:parameter>
              <ac:rich-text-body><p>Hidden content</p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("Click to expand:"));
        assertTrue(result.contains("Hidden content"));
    }

    @Test
    void ignoresTocMacro() {
        String html = """
            <ac:structured-macro ac:name="toc">
              <ac:parameter ac:name="maxLevel">3</ac:parameter>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.isBlank());
    }

    @Test
    void convertsNoFormatMacro() {
        String html = """
            <ac:structured-macro ac:name="noformat">
              <ac:plain-text-body><![CDATA[Plain text here]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("Plain text here"));
    }

    @Test
    void extractsUnknownMacroBody() {
        String html = """
            <ac:structured-macro ac:name="custom-macro">
              <ac:rich-text-body><p>Custom content</p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("Custom content"));
    }

    @Test
    void ignoresImages() {
        String html = "<p>Text before</p><ac:image><ri:attachment ri:filename=\"img.png\"/></ac:image><p>Text after</p>";
        String result = converter.convert(html);
        assertTrue(result.contains("Text before"));
        assertTrue(result.contains("Text after"));
        assertFalse(result.contains("img.png"));
    }

    @Test
    void normalizesWhitespace() {
        String html = "<p>Line 1</p><p></p><p></p><p></p><p>Line 2</p>";
        String result = converter.convert(html);
        assertFalse(result.contains("\n\n\n"));
    }

    @Test
    void handlesEmptyInput() {
        assertEquals("", converter.convert(null));
        assertEquals("", converter.convert(""));
        assertEquals("", converter.convert("   "));
    }

    // === PlantUML Tests (Issue #5) ===

    @Test
    void convertsPlantUmlMacro_format1() {
        String html = """
            <ac:structured-macro ac:name="plantuml">
              <ac:plain-text-body><![CDATA[@startuml
Alice -> Bob: Hello
Bob -> Alice: Hi!
@enduml]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("```plantuml"));
        assertTrue(result.contains("Alice -> Bob: Hello"));
        assertTrue(result.contains("@enduml"));
    }

    @Test
    void convertsPlantUmlMacro_format2_cloud() {
        String html = """
            <ac:structured-macro ac:name="plantuml-cloud" ac:macro-id="abc123">
              <ac:plain-text-body><![CDATA[@startuml
class User {
  +name: String
}
@enduml]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("```plantuml"));
        assertTrue(result.contains("class User"));
    }

    @Test
    void convertsPlantUmlMacro_format3_attachment() {
        String html = """
            <ac:structured-macro ac:name="plantuml">
              <ac:parameter ac:name="attachment">diagram.puml</ac:parameter>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("[PlantUML-Diagramm: diagram.puml]"));
    }

    @Test
    void plantUmlMacro_noBodyNoAttachment_ignored() {
        String html = """
            <ac:structured-macro ac:name="plantuml">
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.isBlank());
    }

    @Test
    void convertsComplexDocument() {
        String html = """
            <h1>API Documentation</h1>
            <p>This service provides a REST API.</p>
            <h2>Endpoints</h2>
            <table>
              <tr><th>Method</th><th>Path</th></tr>
              <tr><td>GET</td><td>/api/users</td></tr>
              <tr><td>POST</td><td>/api/users</td></tr>
            </table>
            <ac:structured-macro ac:name="code">
              <ac:parameter ac:name="language">bash</ac:parameter>
              <ac:plain-text-body><![CDATA[curl http://localhost/api/users]]></ac:plain-text-body>
            </ac:structured-macro>
            <ac:structured-macro ac:name="info">
              <ac:rich-text-body><p>Auth required for POST.</p></ac:rich-text-body>
            </ac:structured-macro>
            <ac:structured-macro ac:name="plantuml">
              <ac:plain-text-body><![CDATA[@startuml
Client -> Server: GET /api/users
Server -> Client: 200 OK
@enduml]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String result = converter.convert(html);
        assertTrue(result.contains("# API Documentation"));
        assertTrue(result.contains("Method | Path"));
        assertTrue(result.contains("```bash"));
        assertTrue(result.contains("curl"));
        assertTrue(result.contains("Info: Auth required"));
        assertTrue(result.contains("```plantuml"));
        assertTrue(result.contains("Client -> Server"));
    }
}
