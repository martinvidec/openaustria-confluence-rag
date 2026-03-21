package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;

public class AdmonitionMacroHandler implements MacroHandler {

    private final String macroName;
    private final String prefix;

    public AdmonitionMacroHandler(String macroName, String prefix) {
        this.macroName = macroName;
        this.prefix = prefix;
    }

    @Override
    public String getMacroName() {
        return macroName;
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
            return "\n" + prefix + " (" + titleParam.text().trim() + "): " + content + "\n";
        }
        return "\n" + prefix + ": " + content + "\n";
    }
}
