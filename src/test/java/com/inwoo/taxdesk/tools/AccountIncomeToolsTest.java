package com.inwoo.taxdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeSource;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 금융소득 집계 — 원천징수 합계의 범위와 현황 분리. */
class AccountIncomeToolsTest {

    private final AccountIncomeRepository repo = mock(AccountIncomeRepository.class);
    private final AccountIncomeTools tools = new AccountIncomeTools(repo);

    @Test
    @DisplayName("원천징수 합계는 과세대상 행만 더한다 — ISA 분리과세 9% 는 기납부세액이 아니다")
    void withheldExcludesSeparateRows() {
        when(repo.findByOwnerIdAndYear("isabu", 2026)).thenReturn(List.of(
                row("하나은행", IncomeSource.LINKED, IncomeType.INTEREST, 9_128_440L, 1_277_980L, TaxTreatment.TAXABLE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.DOMESTIC_DIVIDEND, 12_400_000L, 1_736_000L, TaxTreatment.TAXABLE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.INTEREST, 4_000_000L, 0L, TaxTreatment.TAX_FREE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.INTEREST, 5_120_000L, 460_800L, TaxTreatment.SEPARATE)));

        var s = tools.lookupMyIncome(2026, ctx("isabu"));

        assertThat(s.taxableTotal()).isEqualTo(21_528_440L);
        assertThat(s.excludedTotal()).isEqualTo(9_120_000L);
        assertThat(s.withheldTax()).isEqualTo(3_013_980L);
        assertThat(s.excludedWithheldTax()).isEqualTo(460_800L);
    }

    @Test
    @DisplayName("현황은 기관·출처·유형·과세구분을 분리하고 타사 상태를 따로 표시한다")
    void overviewSeparatesEverything() {
        when(repo.findByOwnerIdAndYear("isabu", 2026)).thenReturn(List.of(
                row("하나은행", IncomeSource.LINKED, IncomeType.INTEREST, 9_128_440L, 1_277_980L, TaxTreatment.TAXABLE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.DOMESTIC_DIVIDEND, 12_400_000L, 1_736_000L, TaxTreatment.TAXABLE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.INTEREST, 4_000_000L, 0L, TaxTreatment.TAX_FREE)));

        var o = tools.overview(2026, ctx("isabu"), null);

        assertThat(o.institutions()).extracting(AccountIncomeTools.InstitutionLine::institution)
                .containsExactly("하나은행", "SKALA증권");
        assertThat(o.institutions().get(1).rows()).isEqualTo(2);
        assertThat(o.byType()).containsEntry("INTEREST", 9_128_440L).containsEntry("DOMESTIC_DIVIDEND", 12_400_000L);
        assertThat(o.byTreatment()).containsEntry("TAX_FREE", 4_000_000L).containsEntry("TAXABLE", 21_528_440L);
        assertThat(o.linkedInstitutions()).containsExactly("하나은행");
        assertThat(o.externalStatus()).isEqualTo("UNKNOWN");
        assertThat(o.notes()).anyMatch(n -> n.contains("타사"));

        assertThat(tools.overview(2026, ctx("isabu"), 0L).externalStatus()).isEqualTo("DECLARED_ZERO");
        assertThat(tools.overview(2026, ctx("isabu"), 5_000_000L).externalStatus()).isEqualTo("DECLARED");
    }

    @Test
    @DisplayName("현황 — 합계·제외분·상품별 행을 싣고 계좌번호는 가린다")
    void overviewCarriesTotalsAndMaskedItems() {
        when(repo.findByOwnerIdAndYear("isabu", 2026)).thenReturn(List.of(
                row("하나은행", IncomeSource.LINKED, IncomeType.INTEREST, 9_128_440L, 1_277_980L, TaxTreatment.TAXABLE),
                row("SKALA증권", IncomeSource.OWN_ACCOUNT, IncomeType.INTEREST, 4_000_000L, 0L, TaxTreatment.TAX_FREE)));

        var o = tools.overview(2026, ctx("isabu"), null);

        assertThat(o.lookupStatus()).isEqualTo(AccountIncomeTools.LOOKUP_OK);
        assertThat(o.taxableTotal()).isEqualTo(9_128_440L);
        assertThat(o.excludedTotal()).isEqualTo(4_000_000L);
        assertThat(o.items()).hasSize(2);
        assertThat(o.items()).allMatch(i -> !i.accountNo().contains("123456"));
    }

    @Test
    @DisplayName("현황 — 행이 없으면 '0원'이 아니라 NO_DATA 다")
    void overviewWithoutRowsIsNoData() {
        when(repo.findByOwnerIdAndYear("isabu", 2026)).thenReturn(List.of());

        var o = tools.overview(2026, ctx("isabu"), null);

        assertThat(o.lookupStatus()).isEqualTo(AccountIncomeTools.LOOKUP_NO_DATA);
        assertThat(o.notes()).anyMatch(n -> n.contains("자료가 없습니다"));
    }

    @Test
    @DisplayName("현황 — 조회 실패는 FAILED 로 돌려주고 금액을 확인된 값처럼 싣지 않는다")
    void failedOverview() {
        var o = AccountIncomeTools.failed(2026, 0L);

        assertThat(o.lookupStatus()).isEqualTo(AccountIncomeTools.LOOKUP_FAILED);
        assertThat(o.institutions()).isEmpty();
        assertThat(o.externalStatus()).isEqualTo("DECLARED_ZERO");
        assertThat(o.notes()).anyMatch(n -> n.contains("0원이라는 뜻이 아니며"));
    }

    private static ToolContext ctx(String user) {
        return new ToolContext(ToolUser.contextOf(user));
    }

    private static AccountIncome row(String inst, IncomeSource src, IncomeType type, long amount, long withheld,
                                     TaxTreatment t) {
        return new AccountIncome(java.util.UUID.randomUUID().toString(), "isabu", "110123456789", 2026,
                inst, src, "상품", type, amount, withheld, t);
    }
}
