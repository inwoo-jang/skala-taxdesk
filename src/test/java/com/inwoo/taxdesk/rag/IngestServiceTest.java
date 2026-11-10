package com.inwoo.taxdesk.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.inwoo.taxdesk.support.FakeVectorStore;
import com.inwoo.taxdesk.support.TestProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Phase 2 검증 — 색인. <b>임베딩 없이 돈다.</b> 실제 {@code tax-docs/} 9종을 읽는다.
 *
 * <p>검증 기준 셋.
 * <ul>
 *   <li>조문 메타 누락 0건 — 출처 없이 인용될 수 있는 청크가 없다</li>
 *   <li>두 번 색인해도 청크 수가 같다 — 중복이 top-k 자리를 차지하지 않는다</li>
 *   <li>법령 문서에는 버전·원문·확인일이 붙고, 청크 본문에 제목이 들어간다 (RG-05 어휘 격차)</li>
 * </ul>
 */
class IngestServiceTest {

    private final FakeVectorStore store = new FakeVectorStore();
    private final IngestService ingest = new IngestService(store, TestProps.props2026());

    @Test
    @DisplayName("9개 문서 · 조문 메타 누락 0건")
    void noMissingArticle() {
        var r = ingest.ingest();

        assertThat(r.files()).isEqualTo(9);
        assertThat(r.chunks()).isGreaterThan(30);
        assertThat(r.missingArticleCount()).as("누락: %s", r.missing()).isZero();
    }

    @Test
    @DisplayName("재색인은 멱등이다 — 두 번 눌러도 청크가 두 배가 되지 않는다")
    void reingestIsIdempotent() {
        int first = ingest.ingest().chunks();
        int stored = store.docs.size();
        ingest.ingest();

        assertThat(store.docs.size()).isEqualTo(stored).isEqualTo(first);
        assertThat(store.deleteCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("법령 문서 청크에는 시행본·원문·확인일이 붙고, 자체 안내는 INTERNAL 이다")
    void lawChunksCarryVersion() {
        ingest.ingest();

        Document law = store.docs.values().stream()
                .filter(d -> String.valueOf(d.getMetadata().get(DocumentMeta.DOC)).startsWith("01-")).findFirst().orElseThrow();
        DocumentMeta m = DocumentMeta.from(law.getMetadata());
        assertThat(m.kind()).isEqualTo("LAW");
        assertThat(m.effectiveYear()).isEqualTo(2026);
        assertThat(m.lawVersion()).contains("제21221호");
        assertThat(m.sourceUrl()).contains("law.go.kr");
        assertThat(m.verifiedOn()).isEqualTo("2026-08-20");

        Document internal = store.docs.values().stream()
                .filter(d -> String.valueOf(d.getMetadata().get(DocumentMeta.DOC)).startsWith("09-")).findFirst().orElseThrow();
        assertThat(DocumentMeta.from(internal.getMetadata()).kind()).isEqualTo("INTERNAL");
        assertThat(DocumentMeta.from(internal.getMetadata()).lawVersion()).isEmpty();
    }

    @Test
    @DisplayName("청크 본문에 문서 제목이 들어간다 — 제목에만 있는 '그로스업'으로도 회수된다")
    void titleIsPartOfChunkText() {
        ingest.ingest();

        assertThat(store.docs.values()).anyMatch(d ->
                String.valueOf(d.getMetadata().get(DocumentMeta.DOC)).startsWith("03-")
                        && d.getText().contains("Gross-up"));
        assertThat(store.docs.values()).allMatch(d -> d.getText().startsWith(
                String.valueOf(d.getMetadata().get(DocumentMeta.TITLE))));
    }

    @Test
    @DisplayName("청크 ID 는 결정적이다")
    void chunkIdIsDeterministic() {
        assertThat(IngestService.chunkId("a.md", "h", 0)).isEqualTo(IngestService.chunkId("a.md", "h", 0));
        assertThat(IngestService.chunkId("a.md", "h", 0)).isNotEqualTo(IngestService.chunkId("a.md", "h", 1));
    }
}
