package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class PlantUmlMacroHandler implements MacroHandler {

    @Override
    public String getMacroName() {
        return "plantuml";
    }

    @Override
    public String handle(Element macroElement) {
        // Format 1 + 2: plain-text-body with UML source
        Element body = macroElement.selectFirst("ac|plain-text-body");
        if (body != null) {
            String umlSource = body.wholeText().trim();
            if (!umlSource.isEmpty()) {
                return "\n```plantuml\n" + umlSource + "\n```\n";
            }
        }

        // Format 3: attachment reference
        Element attachParam = macroElement.selectFirst("ac|parameter[ac:name=attachment]");
        if (attachParam != null && !attachParam.text().isBlank()) {
            return "\n[PlantUML-Diagramm: " + attachParam.text().trim() + "]\n";
        }

        // Fallback: ignore
        return "";
    }
}
