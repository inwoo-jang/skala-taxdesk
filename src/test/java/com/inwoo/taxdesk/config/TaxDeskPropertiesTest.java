package com.inwoo.taxdesk.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.inwoo.taxdesk.support.TestProps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 규칙 세트 검증 — 틀리면 세액이 조용히 틀리는 설정을 기동 시점에 잡는다. */
class TaxDeskPropertiesTest {

    @Test
    @DisplayName("정상 설정은 문제 0건이고 라벨에 연도·버전·대조일이 들어간다")
    void validProps() {
        var p = TestProps.props2026();
        assertThat(p.problems()).isEmpty();
        assertThat(p.rulesUsable()).isTrue();
        assertThat(p.ruleLabel()).isEqualTo("2026 귀속 · 소득세법 법률 제21221호 (시행 2026-07-01) · 대조일 2026-08-20");
    }

    @Test
    @DisplayName("버전·대조일이 비면 문제다")
    void missingVersion() {
        assertThat(TestProps.unversioned().problems())
                .anyMatch(s -> s.contains("rule-version"))
                .anyMatch(s -> s.contains("rule-verified-on"));
    }

    @Test
    @DisplayName("세율표가 비거나, 순서가 꼬이거나, 마지막 구간에 상한이 있으면 문제다")
    void brokenBrackets() {
        assertThat(props(List.of()).problems()).anyMatch(s -> s.contains("비어 있다"));
        assertThat(props(List.of(b(50_000_000L, 0.15, 0), b(14_000_000L, 0.06, 0), b(0, 0.45, 0))).problems())
                .anyMatch(s -> s.contains("앞 구간보다 작거나 같다"));
        assertThat(props(List.of(b(14_000_000L, 0.06, 0), b(50_000_000L, 0.15, 0))).problems())
                .anyMatch(s -> s.contains("마지막 구간"));
        assertThat(props(List.of(b(14_000_000L, 1.5, 0), b(0, 0.45, 0))).problems())
                .anyMatch(s -> s.contains("rate"));
    }

    @Test
    @DisplayName("기본값 — 배당가산율 기본은 현행 10% 다 (옛 11% 가 아니다)")
    void defaults() {
        var p = new TaxDeskProperties(0, null, null, 0, 0, 0, 0, null, null, null, 0, 0, 0, 0, 0, 0, null, null, null);
        assertThat(p.grossUpRate()).isEqualTo(0.10);
        assertThat(p.taxYear()).isEqualTo(2026);
        assertThat(p.rulesUsable()).isFalse();
    }

    private static TaxDeskProperties.Bracket b(long upTo, double rate, long ded) {
        return new TaxDeskProperties.Bracket(upTo, rate, ded);
    }

    private static TaxDeskProperties props(List<TaxDeskProperties.Bracket> brackets) {
        return new TaxDeskProperties(2026, "v", "2026-08-20", 20_000_000L, 0.10, 0.14, 0.10, brackets,
                Map.of(), Map.of(), 2000, 20, 4, 0.25, 0.30, 100, "s", "s", "x");
    }
}
