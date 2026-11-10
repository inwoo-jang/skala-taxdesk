package com.inwoo.taxdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeSource;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * Phase 7 검증 — 계좌 소유자 격리. <b>모델도 API 키도 없이 H2 만으로 돈다.</b>
 *
 * <p>user1 로 로그인한 세션이 user2 의 계좌 금융소득에 닿을 수 있는가.
 * "닿지 못한다"가 아니라 <b>닿을 경로가 없다</b>를 보여야 한다 — 프롬프트로 부탁하는 방식과의 차이.
 * <ul>
 *   <li>조회는 {@code findByOwnerIdAndYear} 뿐이고 소유자는 {@link ToolContext} 에서만 온다</li>
 *   <li>컨텍스트에 소유자가 없으면 조회 자체가 예외다 — 익명 조회 경로가 없다</li>
 *   <li>같은 연도라도 소유자가 다르면 0건, 같은 소유자라도 연도가 다르면 0건</li>
 * </ul>
 *
 * <p>프롬프트 인젝션·역할 탈취 문장은 {@code AdvisorOrderTest}(SafetyAdvisor)와
 * {@code QueryRouterTest}(규칙 거절)가 담당한다. 여기는 데이터 계층이다.
 */
@DataJpaTest
class OwnerIsolationTest {

    @Autowired
    AccountIncomeRepository repository;

    AccountIncomeTools tools;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.save(row("a1", "antman", 2026, "국민은행", IncomeSource.LINKED, "정기예금 이자",
                IncomeType.INTEREST, 8_347_210L, 1_168_600L, TaxTreatment.TAXABLE));
        repository.save(row("a2", "antman", 2026, "SKALA증권", IncomeSource.OWN_ACCOUNT, "국내주식 배당",
                IncomeType.DOMESTIC_DIVIDEND, 6_912_500L, 967_750L, TaxTreatment.TAXABLE));
        repository.save(row("r1", "richman", 2026, "SKALA증권", IncomeSource.OWN_ACCOUNT, "국내주식 배당",
                IncomeType.DOMESTIC_DIVIDEND, 61_250_000L, 8_575_000L, TaxTreatment.TAXABLE));
        repository.save(row("a3", "antman", 2025, "SKALA증권", IncomeSource.OWN_ACCOUNT, "작년 배당",
                IncomeType.DOMESTIC_DIVIDEND, 99_000_000L, 0L, TaxTreatment.TAXABLE));
        tools = new AccountIncomeTools(repository);
    }

    @Test
    @DisplayName("antman 세션은 antman 의 2026 행만 본다 — richman 금액은 어디에도 없다")
    void ownerSeesOnlyOwnRows() {
        var s = tools.lookupMyIncome(2026, ctx("antman"));

        assertThat(s.taxableTotal()).isEqualTo(15_259_710L);
        assertThat(s.items()).hasSize(2);
        assertThat(s.items()).noneMatch(i -> i.amount() == 61_250_000L);
        assertThat(s.domesticDividend()).isNotEqualTo(61_250_000L);
    }

    @Test
    @DisplayName("다른 연도 행은 합계에 들어오지 않는다")
    void yearIsPartOfTheKey() {
        var s = tools.lookupMyIncome(2026, ctx("antman"));
        assertThat(s.items()).noneMatch(i -> i.amount() == 99_000_000L);

        var last = tools.lookupMyIncome(2025, ctx("antman"));
        assertThat(last.domesticDividend()).isEqualTo(99_000_000L);
    }

    @Test
    @DisplayName("데이터가 없는 소유자는 0원이 아니라 '행 없음'이다 — 현황에 기관이 하나도 없다")
    void unknownOwnerHasNoRows() {
        var s = tools.lookupMyIncome(2026, ctx("nobody"));
        assertThat(s.items()).isEmpty();

        var o = tools.overview(2026, ctx("nobody"), null);
        assertThat(o.institutions()).isEmpty();
        assertThat(o.externalStatus()).isEqualTo("UNKNOWN");
        assertThat(o.notes()).anyMatch(n -> n.contains("0원이라는 뜻이 아닙니다"));
    }

    @Test
    @DisplayName("소유자 컨텍스트가 없으면 조회 자체가 예외다 — 익명 경로가 없다")
    void noContextNoLookup() {
        assertThatThrownBy(() -> tools.lookupMyIncome(2026, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.lookupMyIncome(2026, new ToolContext(Map.of("userId", " "))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.lookupMyIncome(2026, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("계좌번호는 마스킹돼 나간다")
    void accountNoIsMasked() {
        var s = tools.lookupMyIncome(2026, ctx("antman"));
        assertThat(s.items()).allMatch(i -> i.accountNo().contains("*"));
    }

    private static ToolContext ctx(String user) {
        return new ToolContext(ToolUser.contextOf(user));
    }

    private static AccountIncome row(String id, String owner, int year, String inst, IncomeSource src,
                                     String product, IncomeType type, long amount, long withheld,
                                     TaxTreatment treatment) {
        return new AccountIncome(id, owner, "110123456789", year, inst, src, product, type, amount, withheld, treatment);
    }
}
