package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class NoFormatMacroHandler implements MacroHandler {

    @Override
    public String getMacroName() {
        return "noformat";
    }

    @Override
    public String handle(Element macroElement) {
        Element body = macroElement.selectFirst("ac|plain-text-body");
        if (body == null) {
            return "";
        }
        return "\n" + body.wholeText().trim() + "\n";
    }
}
