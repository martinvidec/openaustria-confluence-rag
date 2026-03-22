package at.openaustria.confluencerag.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AttachmentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(AttachmentTextExtractor.class);

    public Optional<String> extractText(byte[] content, String fileName) {
        try {
            var resource = new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            var docs = reader.get();
            return docs.stream()
                    .map(doc -> doc.getText())
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((a, b) -> a + "\n" + b);
        } catch (Exception e) {
            log.warn("Konnte Attachment '{}' nicht extrahieren: {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }
}
