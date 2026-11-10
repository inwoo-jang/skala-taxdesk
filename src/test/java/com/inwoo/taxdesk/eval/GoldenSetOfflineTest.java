package com.inwoo.taxdesk.eval;

import static com.inwoo.taxdesk.support.ServiceHarness.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.inwoo.taxdesk.chat.AnswerDto;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxClassification;
import com.inwoo.taxdesk.support.ServiceHarness;
import com.inwoo.taxdesk.tools.TaxCalcTools;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 평가셋 중 <b>모델·API 키·네트워크 없이</b> 채점할 수 있는 항목을 돌린다. 기본 {@code ./gradlew test} 에 포함된다.
 *
 * <ul>
 *   <li>calculation · classification — {@link TaxCalcTools} 를 <b>실제 application.properties 값</b>으로 직접 부른다.
 *       테스트용 설정이 아니라 운영 설정의 세율표를 채점해야 설정 실수가 드러난다</li>
 *   <li>routing · safety · flow — {@link ServiceHarness}(모델 대역 · 가짜 벡터 스토어 · 합성 계좌)로 서비스 전체를 돈다</li>
 * </ul>
 *
 * <p>결과는 {@code build/eval/offline-report.md} 에 쓴다. <b>통과율 분모는 검토된 항목(spec 포함)뿐이고</b>,
 * 미검토 항목은 일치 여부만 따로 적는다. 검토된 항목이 하나라도 실패하면 이 테스트가 실패한다 — 회귀 방지.
 */
class GoldenSetOfflineTest {

    record Result(GoldenSet.Item item, boolean passed, String detail) {
    }

    @Test
    @DisplayName("평가셋 오프라인 항목 — 검토된 항목은 전부 통과하고, 결과 보고서를 남긴다")
    void runOfflineItems() throws Exception {
        GoldenSet set = load();
        TaxCalcTools calc = new TaxCalcTools(realProps());

        List<Result> results = new ArrayList<>();
        for (GoldenSet.Item item : set.items()) {
            if (!item.isOffline()) {
                continue;
            }
            Result r;
            try {
                r = switch (item.kind()) {
                    case "calculation" -> calculation(calc, item);
                    case "classification" -> classification(calc, item);
                    default -> serviceFlow(item);
                };
            } catch (RuntimeException e) {
                r = new Result(item, false, "예외: " + e);
            }
            results.add(r);
        }

        String report = report(set, results);
        Path out = Path.of("build", "eval", "offline-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);

        List<String> countedFailures = results.stream()
                .filter(r -> r.item().countsTowardPassRate() && !r.passed())
                .map(r -> r.item().id() + " — " + r.detail())
                .toList();
        assertThat(countedFailures).as("검토된(spec 포함) 평가 항목 실패").isEmpty();
        assertThat(results).as("오프라인 항목이 하나도 없으면 평가가 안 돈 것이다").isNotEmpty();
    }

    // ------------------------------------------------------------------ 채점

    private static Result calculation(TaxCalcTools calc, GoldenSet.Item item) {
        Map<String, Long> in = item.inputs();
        TaxCalculation c = calc.calculateTax(in.get("financialIncome"), v(in, "grossUpEligibleDividend"),
                v(in, "otherIncome"), v(in, "deduction"), v(in, "prepaidTax"));
        boolean ok = c.decidedTax() == item.expectAmount();
        return new Result(item, ok, "기대 %,d / 실제 %,d".formatted(item.expectAmount(), c.decidedTax()));
    }

    private static Result classification(TaxCalcTools calc, GoldenSet.Item item) {
        Map<String, Long> in = item.inputs();
        TaxClassification c = calc.classifyTaxation(v(in, "interest"), v(in, "domesticDividend"),
                v(in, "foreignDividend"), v(in, "externalAmount"));
        boolean ok = c.taxation().name().equals(item.expectTaxation());
        return new Result(item, ok, "기대 %s / 실제 %s (합계 %,d)".formatted(item.expectTaxation(), c.taxation(),
                c.financialIncome()));
    }

    private static Result serviceFlow(GoldenSet.Item item) {
        ServiceHarness h = new ServiceHarness();
        seed(h, item.seed());
        // 라우터 모델 대역 — 비우면 알아들을 수 없는 답을 내서 규칙 단계·폴백만 쓰이게 한다.
        h.routerModel.reply(item.routerReply() == null ? "?" : item.routerReply());

        String user = item.asUser() == null ? USER : item.asUser();
        String conv = user + ":eval-" + item.id();
        for (String t : item.turns() == null ? List.<String>of() : item.turns()) {
            h.service.ask(user, conv, t);
        }
        int modelBefore = h.main.calls();
        AnswerDto a = h.service.ask(user, conv, item.question());
        boolean modelCalled = h.main.calls() > modelBefore;

        List<String> fails = new ArrayList<>();
        String route = a.route().name();
        if (item.expectRoute() != null && !item.expectRoute().equals(route)) {
            fails.add("라우트 " + route);
        }
        if (item.expectBlocked() != null && item.expectBlocked() != route.equals("OUT_OF_SCOPE")) {
            fails.add("차단=" + route.equals("OUT_OF_SCOPE"));
        }
        if (item.expectModelCall() != null && item.expectModelCall() != modelCalled) {
            fails.add("모델호출=" + modelCalled);
        }
        if (item.expectCalculation() != null && item.expectCalculation() != (a.calculation() != null)) {
            fails.add("계산=" + (a.calculation() != null));
        }
        if (item.expectWrites() != null && item.expectWrites() != h.savedApplications.size()) {
            fails.add("저장 " + h.savedApplications.size() + "건");
        }
        if (item.expectPending() != null && item.expectPending() != (a.pendingAction() != null)) {
            fails.add("미리보기=" + (a.pendingAction() != null));
        }
        if (item.expectNotice() != null && a.notices().stream().noneMatch(n -> n.contains(item.expectNotice()))) {
            fails.add("안내 없음: " + item.expectNotice());
        }
        if (item.expectNoNotice() != null && a.notices().stream().anyMatch(n -> n.contains(item.expectNoNotice()))) {
            fails.add("있으면 안 되는 안내: " + item.expectNoNotice());
        }
        if (item.expectAnswer() != null && (a.answer() == null || !a.answer().contains(item.expectAnswer()))) {
            fails.add("답변에 없음: " + item.expectAnswer());
        }
        if (item.expectToolResult() != null) {
            String[] nr = item.expectToolResult().split("=", 2);
            boolean hit = a.toolCalls().stream().anyMatch(t -> t.name().equals(nr[0]) && nr[1].equals(t.result()));
            if (!hit) {
                fails.add("도구 결과 없음: " + item.expectToolResult() + " / 실제 " + a.toolCalls().stream()
                        .map(t -> t.name() + "=" + t.result()).collect(Collectors.joining(", ")));
            }
        }
        if (item.expectNotInResponse() != null && a.toString().contains(item.expectNotInResponse())) {
            fails.add("응답에 노출: " + item.expectNotInResponse());
        }
        String summary = "route=%s model=%s calc=%s writes=%d".formatted(route, modelCalled,
                a.calculation() != null, h.savedApplications.size());
        return new Result(item, fails.isEmpty(), fails.isEmpty() ? summary : String.join(" · ", fails) + " (" + summary + ")");
    }

    private static void seed(ServiceHarness h, String seed) {
        switch (seed == null ? "none" : seed) {
            case "antman" -> h.seedAntman();
            case "isabu" -> h.seedIsabu();
            case "empty" -> when(h.incomeRepo.findByOwnerIdAndYear(USER, 2026)).thenReturn(List.of());
            case "failing" -> when(h.incomeRepo.findByOwnerIdAndYear(anyString(), anyInt()))
                    .thenThrow(new DataAccessResourceFailureException("eval: 저장소 장애"));
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 보고서

    private static String report(GoldenSet set, List<Result> results) {
        StringBuilder sb = new StringBuilder("# 평가셋 오프라인 결과\n\n");
        sb.append("모델·API 키 없이 채점한 항목만 담는다. 전체 %d건 중 오프라인 %d건, 모델 필요 %d건(미실행).\n\n"
                .formatted(set.items().size(), results.size(), set.items().size() - results.size()));

        List<Result> counted = results.stream().filter(r -> r.item().countsTowardPassRate()).toList();
        List<Result> unreviewed = results.stream().filter(r -> !r.item().countsTowardPassRate()).toList();
        sb.append("| 구분 | 통과 / 분모 |\n|---|---|\n");
        sb.append("| 검토된 항목 (spec) | %d / %d |\n".formatted(passed(counted), counted.size()));
        for (String split : List.of("tuning", "holdout")) {
            List<Result> s = counted.stream().filter(r -> split.equals(r.item().split())).toList();
            sb.append("| └ %s | %d / %d |\n".formatted(split, passed(s), s.size()));
        }
        Map<String, List<Result>> byKind = counted.stream()
                .collect(Collectors.groupingBy(r -> r.item().kind(), LinkedHashMap::new, Collectors.toList()));
        byKind.forEach((k, v) -> sb.append("| └ %s | %d / %d |\n".formatted(k, passed(v), v.size())));
        sb.append("| 미검토 — 기대값 일치 (통과율 아님) | %d / %d |\n\n".formatted(passed(unreviewed), unreviewed.size()));

        sb.append("미검토 항목의 '일치'는 독립 계산과 구현이 같은 값을 냈다는 뜻일 뿐 세무상 정답이라는 뜻이 아니다.\n\n");
        sb.append("| ID | 종류 | split | 검토 | 결과 | 세부 |\n|---|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append("| %s | %s | %s | %s | %s | %s |\n".formatted(r.item().id(), r.item().kind(), r.item().split(),
                    r.item().reviewStatus(), r.passed() ? "통과" : "**실패**", r.detail().replace("|", "/")));
        }
        return sb.toString();
    }

    private static int passed(List<Result> rs) {
        return (int) rs.stream().filter(Result::passed).count();
    }

    // ------------------------------------------------------------------ 로딩

    static GoldenSet load() throws Exception {
        try (InputStream in = new ClassPathResource("golden.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(in, GoldenSet.class);
        }
    }

    /** 운영 설정 파일을 Spring 과 같은 방식으로 읽어 바인딩한다. */
    static TaxDeskProperties realProps() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new PropertiesPropertySourceLoader().load("app", new ClassPathResource("application.properties"))
                .forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env)).bind("taxdesk", TaxDeskProperties.class).get();
    }

    private static long v(Map<String, Long> in, String key) {
        Long x = in.get(key);
        return x == null ? 0L : x;
    }
}
