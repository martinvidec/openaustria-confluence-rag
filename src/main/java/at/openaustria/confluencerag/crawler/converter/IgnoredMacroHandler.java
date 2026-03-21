package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;

public class IgnoredMacroHandler implements MacroHandler {

    private final String macroName;

    public IgnoredMacroHandler(String macroName) {
        this.macroName = macroName;
    }

    @Override
    public String getMacroName() {
        return macroName;
    }

    @Override
    public String handle(Element macroElement) {
        return "";
    }
}
