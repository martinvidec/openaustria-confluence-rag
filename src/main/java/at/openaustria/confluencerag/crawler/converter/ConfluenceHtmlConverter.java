package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConfluenceHtmlConverter {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceHtmlConverter.class);
    private final Map<String, MacroHandler> macroHandlers = new HashMap<>();

    public ConfluenceHtmlConverter(List<MacroHandler> handlers) {
        for (MacroHandler handler : handlers) {
            macroHandlers.put(handler.getMacroName(), handler);
        }
        // Register admonition macros
        registerIfAbsent("info", new AdmonitionMacroHandler("info", "Info"));
        registerIfAbsent("note", new AdmonitionMacroHandler("note", "Hinweis"));
        registerIfAbsent("warning", new AdmonitionMacroHandler("warning", "Warnung"));
        registerIfAbsent("tip", new AdmonitionMacroHandler("tip", "Tipp"));
        // Register ignored macros
        for (String name : List.of("toc", "children", "include", "recently-updated",
                "content-by-label", "page-tree", "livesearch")) {
            registerIfAbsent(name, new IgnoredMacroHandler(name));
        }
    }

    private void registerIfAbsent(String name, MacroHandler handler) {
        macroHandlers.putIfAbsent(name, handler);
    }

    public String convert(String storageFormat) {
        if (storageFormat == null || storageFormat.isBlank()) {
            return "";
        }

        Document doc = Jsoup.parse(storageFormat, "", Parser.xmlParser());
        StringBuilder sb = new StringBuilder();
        convertNode(doc, sb, 0);

        return normalizeWhitespace(sb.toString());
    }

    private void convertNode(Node node, StringBuilder sb, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = textNode.getWholeText();
            if (!text.isBlank()) {
                sb.append(text);
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase();

        // Handle structured macros
        if (tagName.equals("ac:structured-macro")) {
            handleMacro(element, sb);
            return;
        }

        // Handle images — ignore
        if (tagName.equals("ac:image") || tagName.equals("img")) {
            return;
        }

        // Handle Confluence links
        if (tagName.equals("ac:link")) {
            Element linkBody = element.selectFirst("ac|link-body, ac|plain-text-link-body");
            if (linkBody != null) {
                sb.append(linkBody.text());
            } else {
                // Try ri:page reference
                Element pageRef = element.selectFirst("ri|page");
                if (pageRef != null) {
                    sb.append(pageRef.attr("ri:content-title"));
                }
            }
            return;
        }

        switch (tagName) {
            case "h1" -> { sb.append("\n\n# "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "h2" -> { sb.append("\n\n## "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "h3" -> { sb.append("\n\n### "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "h4" -> { sb.append("\n\n#### "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "h5" -> { sb.append("\n\n##### "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "h6" -> { sb.append("\n\n###### "); convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "p" -> { convertChildren(element, sb, listDepth); sb.append("\n\n"); }
            case "br" -> sb.append("\n");
            case "a" -> {
                String href = element.attr("href");
                String text = element.text();
                if (!href.isEmpty() && !text.isEmpty()) {
                    sb.append(text).append(" (").append(href).append(")");
                } else {
                    sb.append(text);
                }
            }
            case "ul" -> {
                sb.append("\n");
                for (Element li : element.children()) {
                    if (li.tagName().equalsIgnoreCase("li")) {
                        sb.append("  ".repeat(listDepth)).append("- ");
                        convertChildren(li, sb, listDepth + 1);
                        sb.append("\n");
                    }
                }
            }
            case "ol" -> {
                sb.append("\n");
                int index = 1;
                for (Element li : element.children()) {
                    if (li.tagName().equalsIgnoreCase("li")) {
                        sb.append("  ".repeat(listDepth)).append(index++).append(". ");
                        convertChildren(li, sb, listDepth + 1);
                        sb.append("\n");
                    }
                }
            }
            case "table" -> convertTable(element, sb);
            case "strong", "b" -> convertChildren(element, sb, listDepth);
            case "em", "i" -> convertChildren(element, sb, listDepth);
            case "code" -> {
                sb.append("`");
                convertChildren(element, sb, listDepth);
                sb.append("`");
            }
            case "pre" -> {
                sb.append("\n```\n");
                sb.append(element.wholeText());
                sb.append("\n```\n");
            }
            default -> convertChildren(element, sb, listDepth);
        }
    }

    private void convertChildren(Node node, StringBuilder sb, int listDepth) {
        for (Node child : node.childNodes()) {
            convertNode(child, sb, listDepth);
        }
    }

    private void handleMacro(Element macroElement, StringBuilder sb) {
        String macroName = macroElement.attr("ac:name");
        if (macroName.isEmpty()) {
            return;
        }

        // Find handler: exact match first, then prefix match (for plantuml-cloud etc.)
        MacroHandler handler = macroHandlers.get(macroName);
        if (handler == null) {
            handler = macroHandlers.entrySet().stream()
                    .filter(e -> macroName.startsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (handler != null) {
            String result = handler.handle(macroElement);
            sb.append(result);
            return;
        }

        // Default: extract body content if available
        Element richBody = macroElement.selectFirst("ac|rich-text-body");
        if (richBody != null) {
            String text = richBody.text().trim();
            if (!text.isEmpty()) {
                sb.append("\n").append(text).append("\n");
            }
            return;
        }
        Element plainBody = macroElement.selectFirst("ac|plain-text-body");
        if (plainBody != null) {
            String text = plainBody.wholeText().trim();
            if (!text.isEmpty()) {
                sb.append("\n").append(text).append("\n");
            }
        }
    }

    private void convertTable(Element table, StringBuilder sb) {
        sb.append("\n");
        for (Element row : table.select("tr")) {
            List<String> cells = row.select("th, td").stream()
                    .map(cell -> cell.text().trim())
                    .toList();
            sb.append(String.join(" | ", cells)).append("\n");
        }
        sb.append("\n");
    }

    private String normalizeWhitespace(String text) {
        // Collapse 3+ consecutive newlines to 2
        String result = text.replaceAll("\n{3,}", "\n\n");
        return result.strip();
    }
}
