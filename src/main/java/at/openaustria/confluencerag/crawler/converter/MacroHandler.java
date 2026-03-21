package at.openaustria.confluencerag.crawler.converter;

import org.jsoup.nodes.Element;

public interface MacroHandler {

    String getMacroName();

    String handle(Element macroElement);
}
