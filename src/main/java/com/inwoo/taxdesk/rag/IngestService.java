package com.inwoo.taxdesk.rag;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Phase 2 — 세법 요약 문서를 청크로 쪼개 벡터 스토어에 넣는다.
 *
 * <p><b>청크 경계를 헤딩(조문)에 맞춘다.</b> 고정 길이로 자르면 조문 번호와 본문이
 * 다른 청크로 흩어져 출처가 깨진다. "소득세법 §17③" 이 A 청크에, "100분의 10" 이 B 청크에
 * 들어가면 둘 중 무엇을 인용해도 틀린 출처가 된다.
 *
 * <p>문서 형식 규약 (docs/SOURCES.md §3):
 * <pre>
 *   # 제목
 *   &gt; 출처: 소득세법 §14 · §62
 *   &gt; 귀속연도: 2026
 *   &gt; 문서 성격: LAW
 *   &gt; 법령버전: 소득세법 법률 제21221호 (시행 2026-07-01)   ← 선택. 없으면 빈 값
 *   &gt; 원문: 국가법령정보센터(law.go.kr) · docs/raw_law/소득세법.txt
 *   &gt; 확인일: 2026-08-20
 *
 *   ## 헤딩            ← 청크 경계
 *   소득세법 §14③11호   ← 헤딩 바로 아래 조문 재기입. 청크 하나만 봐도 출처가 완성된다
 *   본문...
 * </pre>
 *
 * <p>두 가지를 더 지킨다.
 * <ul>
 *   <li><b>청크 ID 는 결정적이다</b> (파일명 + 헤딩 + 순번). 같은 문서를 다시 색인하면
 *       같은 ID 를 먼저 지우고 넣으므로 두 번 눌러도 청크가 두 배가 되지 않는다.
 *       예전에는 색인할 때마다 중복이 쌓였고, 중복 청크가 top-k 자리를 차지했다</li>
 *   <li><b>청크 본문 앞에 문서 제목을 넣는다.</b> 제목에만 있는 낱말(예: "그로스업")이 본문에 없으면
 *       고객 용어로 물었을 때 회수되지 않았다 (RESULTS RG-05)</li>
 * </ul>
 *
 * <p>검증 기준: 인제스트 후 <b>조문 메타 누락 0건</b>. 하나라도 비면 그 청크는 출처 없이
 * 인용될 수 있고, 그건 검증표상 실패다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final Pattern HEADING = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONT_SOURCE = Pattern.compile("^>\\s*출처:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONT_YEAR = Pattern.compile("^>\\s*귀속연도:\\s*(\\d{4})", Pattern.MULTILINE);
    private static final Pattern FRONT_KIND = Pattern.compile("^>\\s*문서 성격:\\s*(\\w+)", Pattern.MULTILINE);
    private static final Pattern FRONT_VERSION = Pattern.compile("^>\\s*법령버전:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONT_URL = Pattern.compile("^>\\s*원문:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONT_VERIFIED = Pattern.compile("^>\\s*확인일:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern TITLE = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    /** 헤딩 바로 아래 줄에 다시 적은 조문. 이게 있으면 청크 단독으로 출처가 완성된다. */
    private static final Pattern ARTICLE_LINE =
            Pattern.compile("^\\s*((?:소득세법|조세특례제한법|국세기본법|세무사법)\\s*§[^\\n]*)$", Pattern.MULTILINE);

    private static final int MIN_CHARS = 50;

    private final VectorStore vectorStore;
    private final TaxDeskProperties props;

    public IngestService(VectorStore vectorStore, TaxDeskProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    public IngestResult ingest() {
        List<Document> docs = new ArrayList<>();
        List<String> missingArticle = new ArrayList<>();
        int files = 0;

        for (Resource r : load()) {
            files++;
            String name = r.getFilename();
            String text = read(r);
            String title = first(TITLE, text, name);
            String fallbackArticle = first(FRONT_SOURCE, text, "");
            int year = Integer.parseInt(first(FRONT_YEAR, text, "0"));
            String kind = first(FRONT_KIND, text, DocumentMeta.KIND_LAW);
            // 자체 안내는 법령 버전이 없다. 헤더의 "(자체 안내 — 법령 아님)" 같은 설명 문구를 버전으로 싣지 않는다.
            String version = DocumentMeta.KIND_LAW.equals(kind) ? first(FRONT_VERSION, text, "") : "";
            String url = first(FRONT_URL, text, "");
            String verified = first(FRONT_VERIFIED, text, "");

            int index = 0;
            for (Chunk c : split(text)) {
                if (c.body().length() < MIN_CHARS) {
                    continue;
                }
                // 헤딩 아래 조문이 없으면 문서 헤더의 출처로 채운다. 그래도 없으면 누락으로 센다.
                String article = c.article().isBlank() ? fallbackArticle : c.article();
                if (article.isBlank()) {
                    missingArticle.add(name + " / " + c.heading());
                }
                DocumentMeta meta = new DocumentMeta(name, title, article, year, kind, version, url, verified);
                String body = c.heading().isBlank()
                        ? title + "\n" + c.body()
                        : title + " — " + c.heading() + "\n" + c.body();
                docs.add(new Document(chunkId(name, c.heading(), index++), body, meta.toMap()));
            }
        }

        if (!docs.isEmpty()) {
            // 같은 ID 를 먼저 지운다. 지울 것이 없어도 예외가 나지 않는다 (SimpleVectorStore 는 map.remove).
            try {
                vectorStore.delete(docs.stream().map(Document::getId).toList());
            } catch (RuntimeException e) {
                log.warn("기존 청크 삭제 실패 — 중복이 남을 수 있다: {}", e.toString());
            }
            vectorStore.add(docs);
        }
        IngestResult result = new IngestResult(files, docs.size(), missingArticle.size(), missingArticle);
        log.info("인제스트 완료 — 파일 {} · 청크 {} · 조문 메타 누락 {}",
                result.files(), result.chunks(), result.missingArticleCount());
        return result;
    }

    /** 결정적 ID. 같은 문서·같은 헤딩·같은 순번이면 항상 같은 값이다. */
    static String chunkId(String doc, String heading, int index) {
        return UUID.nameUUIDFromBytes((doc + "#" + heading + "#" + index).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** 헤딩(조문) 경계로 자른다. 헤딩이 없는 문서는 통째로 한 청크다. */
    private List<Chunk> split(String text) {
        List<Chunk> out = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            headings.add(m.group(1).trim());
        }
        if (spans.isEmpty()) {
            out.add(new Chunk("", stripFrontMatter(text), ""));
            return out;
        }
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[1];
            int to = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : text.length();
            String body = text.substring(from, to).trim();
            Matcher a = ARTICLE_LINE.matcher(body);
            out.add(new Chunk(headings.get(i), body, a.find() ? a.group(1).trim() : ""));
        }
        return out;
    }

    private static String stripFrontMatter(String text) {
        return text.replaceAll("(?m)^[#>].*$", "").trim();
    }

    private Resource[] load() {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources(props.docsLocation() + "*.md");
        } catch (IOException e) {
            throw new IllegalStateException("세법 문서를 읽지 못했습니다: " + props.docsLocation(), e);
        }
    }

    private static String read(Resource r) {
        try {
            return StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("문서 읽기 실패: " + r.getFilename(), e);
        }
    }

    private static String first(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private record Chunk(String heading, String body, String article) {
    }

    /**
     * @param missingArticleCount <b>0 이어야 한다.</b> 하나라도 있으면 출처 없이 인용될 수 있는
     *                            청크가 존재한다는 뜻이고, 검증표상 실패다
     */
    public record IngestResult(int files, int chunks, int missingArticleCount, List<String> missing) {
    }
}
