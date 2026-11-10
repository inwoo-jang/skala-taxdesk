package com.inwoo.taxdesk.support;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import java.util.List;
import java.util.Map;

/** 2026 귀속 세율표 — application.properties 와 같은 값이어야 한다 (실험 E11). */
public final class TestProps {

    private TestProps() {
    }

    public static List<TaxDeskProperties.Bracket> brackets2026() {
        return List.of(
                new TaxDeskProperties.Bracket(14_000_000L, 0.06, 0L),
                new TaxDeskProperties.Bracket(50_000_000L, 0.15, 1_260_000L),
                new TaxDeskProperties.Bracket(88_000_000L, 0.24, 5_760_000L),
                new TaxDeskProperties.Bracket(150_000_000L, 0.35, 15_440_000L),
                new TaxDeskProperties.Bracket(300_000_000L, 0.38, 19_940_000L),
                new TaxDeskProperties.Bracket(500_000_000L, 0.40, 25_940_000L),
                new TaxDeskProperties.Bracket(1_000_000_000L, 0.42, 35_940_000L),
                new TaxDeskProperties.Bracket(0L, 0.45, 65_940_000L));
    }

    public static TaxDeskProperties props2026() {
        return new TaxDeskProperties(2026, "소득세법 법률 제21221호 (시행 2026-07-01)", "2026-08-20",
                20_000_000L, 0.10, 0.14, 0.10, brackets2026(),
                Map.of("antman", "x"), Map.of("admin", "x"), 2000, 20, 4, 0.25, 0.30, 100,
                "stub", "stub", "classpath:/tax-docs/");
    }

    /** 버전 정보가 빠진 규칙 세트 — 서비스는 이것으로 판정하면 안 된다. */
    public static TaxDeskProperties unversioned() {
        return new TaxDeskProperties(2026, "", "", 20_000_000L, 0.10, 0.14, 0.10, brackets2026(),
                Map.of(), Map.of(), 2000, 20, 4, 0.25, 0.30, 100, "stub", "stub", "classpath:/tax-docs/");
    }
}
