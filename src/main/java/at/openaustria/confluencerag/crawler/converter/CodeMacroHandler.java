package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class CodeMacroHandler implements MacroHandler {

    @Override
    public String getMacroName() {
        return "code";
    }

    @Override
    public String handle(Element macroElement) {
        String language = "";
        Element langParam = macroElement.selectFirst("ac|parameter[ac:name=language]");
        if (langParam != null) {
            language = langParam.text().trim();
        }

        Element body = macroElement.selectFirst("ac|plain-text-body");
        if (body == null) {
            return "";
        }

        String code = body.wholeText().trim();
        return "\n```" + language + "\n" + code + "\n```\n";
    }
}
