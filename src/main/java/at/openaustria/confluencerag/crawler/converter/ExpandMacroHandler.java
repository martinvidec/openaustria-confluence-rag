package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class ExpandMacroHandler implements MacroHandler {

    @Override
    public String getMacroName() {
        return "expand";
    }

    @Override
    public String handle(Element macroElement) {
        Element richBody = macroElement.selectFirst("ac|rich-text-body");
        if (richBody == null) {
            return "";
        }
        String content = richBody.text().trim();
        if (content.isEmpty()) {
            return "";
        }

        Element titleParam = macroElement.selectFirst("ac|parameter[ac:name=title]");
        if (titleParam != null && !titleParam.text().isBlank()) {
            return "\n" + titleParam.text().trim() + ":\n" + content + "\n";
        }
        return "\n" + content + "\n";
    }
}
