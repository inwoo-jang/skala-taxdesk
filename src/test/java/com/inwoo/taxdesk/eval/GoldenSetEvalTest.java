package com.inwoo.taxdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.inwoo.taxdesk.rag.IngestService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 평가셋 중 <b>모델과 실제 색인이 필요한</b> 항목(offline=false)을 돈다. 유료 API 를 호출한다.
 *
 * <p>기본 test 에서 빠진다. 실행: {@code OPENAI_API_KEY_SKALA1=... ./gradlew test -Peval --tests '*GoldenSetEvalTest*'}
 * <br>호출 수 = 색인 임베딩(문서 9개) + 항목당 라우터 1회 · 임베딩 1회 · 주 모델 1회. 항목 6건 기준 소량이지만
 * 단가는 실행 시점에 확인하고 보고서에 함께 적는다.
 *
 * <p>통과율은 검토된 항목만 분모로 쓴다 — 지금 regulation 항목은 전부 unreviewed 라 <b>통과율은 측정되지 않고</b>,
 * 적중 여부만 보고서에 남는다.
 */
@Tag("eval")
@SpringBootTest
class GoldenSetEvalTest {

    @Autowired
    EvalService eval;

    @Autowired
    IngestService ingest;

    @Test
    @DisplayName("모델 필요 항목 — 실제 모델·색인으로 채점하고 보고서를 남긴다")
    void runOnlineItems() throws Exception {
        ingest.ingest();
        GoldenSet all = GoldenSetOfflineTest.load();
        List<GoldenSet.Item> online = all.items().stream().filter(i -> !i.isOffline()).toList();

        EvalService.EvalReport r = eval.run(new GoldenSet(online));

        StringBuilder sb = new StringBuilder("# 평가셋 온라인 결과 (실제 모델)\n\n");
        sb.append("항목 %d건 · 통과율 분모(검토됨) %d건 · 통과 %d건 · 미검토 %d건\n\n"
                .formatted(r.total(), r.counted(), r.passed(), r.unreviewed()));
        sb.append(r.counted() == 0 ? "검토된 항목이 없어 통과율은 측정되지 않았다.\n\n" : "통과율 %.1f%%\n\n".formatted(r.passRate() * 100));
        sb.append("| ID | 종류 | 집계 | 결과 | 세부 |\n|---|---|---|---|---|\n");
        r.results().forEach(x -> sb.append("| %s | %s | %s | %s | %s |\n".formatted(
                x.id(), x.kind(), x.counted() ? "분모" : "미검토", x.passed() ? "통과" : "실패", x.detail().replace("|", "/"))));

        Path out = Path.of("build", "eval", "online-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);

        assertThat(r.total()).isEqualTo(online.size());
    }
}
