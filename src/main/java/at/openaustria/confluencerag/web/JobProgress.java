package at.openaustria.confluencerag.web;

public record JobProgress(
    String phase,
    String detail,
    int currentItem,
    int totalItems,
    int chunksProcessed,
    int errors,
    String currentSpace
) {
    public int percentComplete() {
        return totalItems > 0 ? (currentItem * 100) / totalItems : 0;
    }
}
