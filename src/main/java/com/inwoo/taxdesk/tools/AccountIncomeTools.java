package com.inwoo.taxdesk.tools;

import com.inwoo.taxdesk.advisor.Masker;
import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeSource;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 4 — 내 금융소득 조회. 자사 계좌 + 마이데이터 연동분.
 *
 * <p>{@code lookupMyIncome(year)} — "내" 가 어디서 오는지가 전부다.
 * 사용자 입력이나 모델이 지어낸 ID 가 아니라 {@link ToolUser} 가 세션에서 꺼낸 값만 쓰고,
 * 조회는 {@code findByOwnerIdAndYear} 로 소유자 조건을 쿼리에 박는다.
 *
 * <p><b>파라미터에 기관명이나 고객 ID 를 두지 않는다.</b> 프롬프트로 "타인 것은 조회하지 마"라고
 * 부탁하면 우회할 여지가 남지만, 넣을 자리가 없으면 우회할 것도 없다.
 *
 * <p>비과세·분리과세 상품은 <b>합계에서 빼되 목록에는 남긴다.</b> 빼고 안 보여 주면
 * 고객이 "내 ISA 는 왜 빠졌냐"고 묻게 되고, 그 답을 모델이 지어내게 된다.
 *
 * <p><b>원천징수 합계도 과세대상 행만 더한다.</b> ISA 초과분의 9% 분리과세 세액은 그 소득과 함께
 * 과세가 끝난 돈이라 종합소득 결정세액에서 빼는 기납부세액이 아니다. 예전 구현은 전 행을 더해
 * 기납부세액을 부풀렸다 — 그만큼 "추가 납부할 세액"이 작게 나온다.
 */
@Component
public class AccountIncomeTools {

    private final AccountIncomeRepository repository;

    public AccountIncomeTools(AccountIncomeRepository repository) {
        this.repository = repository;
    }

    @Tool(description = """
            로그인한 고객 본인의 해당 연도 금융소득(이자·배당)을 조회한다.
            다른 사람이나 다른 금융기관의 계좌는 조회할 수 없다.
            비과세·분리과세 상품은 종합과세 판정 대상에서 제외된 금액으로 따로 표시된다.""")
    public IncomeSummary lookupMyIncome(
            @ToolParam(description = "귀속연도. 예: 2026") int year,
            ToolContext context) {

        String ownerId = ToolUser.of(context);
        List<AccountIncome> rows = repository.findByOwnerIdAndYear(ownerId, year);

        long interest = sum(rows, IncomeType.INTEREST, true);
        long domestic = sum(rows, IncomeType.DOMESTIC_DIVIDEND, true);
        long foreign = sum(rows, IncomeType.FOREIGN_DIVIDEND, true);
        long withheld = rows.stream()
                .filter(AccountIncome::countsTowardThreshold)
                .mapToLong(AccountIncome::getWithheldTax).sum();
        long excludedWithheld = rows.stream()
                .filter(r -> !r.countsTowardThreshold())
                .mapToLong(AccountIncome::getWithheldTax).sum();
        long excluded = rows.stream()
                .filter(r -> !r.countsTowardThreshold())
                .mapToLong(AccountIncome::getAmount).sum();

        List<Item> items = rows.stream().map(AccountIncomeTools::item).toList();

        return new IncomeSummary(year, interest, domestic, foreign,
                interest + domestic + foreign, excluded, withheld, excludedWithheld, items);
    }

    /**
     * "내 금융소득을 한눈에" — 기관·출처·유형·과세구분별로 나눈 현황.
     *
     * <p>{@link #lookupMyIncome} 이 판정용 합계라면 이것은 <b>확인 범위를 보여주는</b> 화면용이다.
     * 어느 기관의 자료가 들어와 있고, 어느 유형이 얼마이며, 무엇이 판정에서 빠졌는지를 분리한다.
     * 여기 없는 기관은 "0원"이 아니라 <b>"자료 없음"</b>이다 — 연동하지 않았거나 지급이 없었거나
     * 둘 중 무엇인지 이 데이터로는 알 수 없고, 그래서 그렇게 적는다.
     *
     * @param externalDeclared 고객이 대화에서 말한 타사 금액. {@code null} 이면 미확인
     */
    public IncomeOverview overview(int year, ToolContext context, Long externalDeclared) {
        String ownerId = ToolUser.of(context);
        List<AccountIncome> rows = repository.findByOwnerIdAndYear(ownerId, year);

        Map<String, InstitutionLine> byInstitution = new LinkedHashMap<>();
        for (AccountIncome r : rows) {
            String key = r.getInstitution() + "|" + r.getSource();
            InstitutionLine line = byInstitution.getOrDefault(key,
                    new InstitutionLine(r.getInstitution(), r.getSource().name(), "CONFIRMED", 0, 0L, 0L));
            byInstitution.put(key, new InstitutionLine(line.institution(), line.source(), line.status(),
                    line.rows() + 1, line.amount() + r.getAmount(), line.withheldTax() + r.getWithheldTax()));
        }

        Map<String, Long> byType = new LinkedHashMap<>();
        for (IncomeType t : IncomeType.values()) {
            byType.put(t.name(), sum(rows, t, true));
        }
        Map<String, Long> byTreatment = new LinkedHashMap<>();
        for (TaxTreatment t : TaxTreatment.values()) {
            byTreatment.put(t.name(), rows.stream()
                    .filter(r -> r.getTreatment() == t)
                    .mapToLong(AccountIncome::getAmount).sum());
        }

        String externalStatus = externalStatusOf(externalDeclared);
        String lookupStatus = rows.isEmpty() ? LOOKUP_NO_DATA : LOOKUP_OK;

        long taxableTotal = rows.stream().filter(AccountIncome::countsTowardThreshold)
                .mapToLong(AccountIncome::getAmount).sum();
        long excludedTotal = rows.stream().filter(r -> !r.countsTowardThreshold())
                .mapToLong(AccountIncome::getAmount).sum();
        List<Item> items = rows.stream().map(AccountIncomeTools::item).toList();

        List<String> notes = new ArrayList<>();
        if (rows.isEmpty()) {
            // 조회는 성공했지만 행이 없다. "확인된 0원"이 아니라 "이 범위에 자료가 없다"이다.
            notes.add("%d년 귀속으로 조회된 자사·연동 기관 금융소득 자료가 없습니다. 지급이 없었는지, 아직 반영되지 않았는지는 이 자료로 알 수 없습니다."
                    .formatted(year));
        }
        notes.add("자사(OWN_ACCOUNT)와 마이데이터 연동(LINKED) 기관의 자료만 포함됩니다. "
                + "연동되지 않은 기관은 여기 나타나지 않으며, 그 기관의 소득이 0원이라는 뜻이 아닙니다.");
        notes.add("금액은 조회 시점까지 확인된 지급분입니다. 연말까지 추가 지급이 있으면 달라집니다.");
        notes.add(externalNote(externalDeclared));

        return new IncomeOverview(year, LocalDate.now(), lookupStatus, taxableTotal, excludedTotal,
                List.copyOf(byInstitution.values()), byType, byTreatment,
                externalDeclared, externalStatus,
                rows.stream().filter(r -> r.getSource() == IncomeSource.LINKED)
                        .map(AccountIncome::getInstitution).distinct().toList(),
                items, notes);
    }

    /**
     * 조회 자체가 실패했을 때의 현황. 금액 칸은 0 이 아니라 "모름"이다 — 화면은 {@code lookupStatus} 를 먼저 본다.
     */
    public static IncomeOverview failed(int year, Long externalDeclared) {
        return new IncomeOverview(year, LocalDate.now(), LOOKUP_FAILED, 0L, 0L, List.of(), Map.of(), Map.of(),
                externalDeclared, externalStatusOf(externalDeclared), List.of(), List.of(),
                List.of("계좌 자료 조회에 실패했습니다. 금융소득이 0원이라는 뜻이 아니며, 잠시 후 다시 확인해 주세요.",
                        externalNote(externalDeclared)));
    }

    public static final String LOOKUP_OK = "OK";
    public static final String LOOKUP_NO_DATA = "NO_DATA";
    public static final String LOOKUP_FAILED = "FAILED";

    private static String externalStatusOf(Long externalDeclared) {
        return externalDeclared == null ? "UNKNOWN" : externalDeclared == 0 ? "DECLARED_ZERO" : "DECLARED";
    }

    private static String externalNote(Long externalDeclared) {
        return externalDeclared == null
                ? "타사(미연동) 금융소득은 아직 확인되지 않았습니다. 없으면 상담창에 \"타사 소득 없어요\"라고 알려 주세요."
                : "타사 금융소득 %,d원은 고객님이 알려주신 값이며 저희가 확인한 값이 아닙니다. 위 연동 기관의 소득이 그 금액에 포함돼 있지 않은지 확인해 주세요."
                        .formatted(externalDeclared);
    }

    private static Item item(AccountIncome r) {
        return new Item(r.getInstitution(), Masker.maskAccountNo(r.getAccountNo()), r.getProductName(),
                r.getType(), r.getTreatment(), r.getSource().name(), r.getAmount(), r.getWithheldTax());
    }

    private static long sum(List<AccountIncome> rows, IncomeType type, boolean taxableOnly) {
        return rows.stream()
                .filter(r -> r.getType() == type)
                .filter(r -> !taxableOnly || r.countsTowardThreshold())
                .mapToLong(AccountIncome::getAmount)
                .sum();
    }

    /**
     * @param taxableTotal         종합과세 판정에 들어가는 합계. 비과세·분리과세는 빠져 있다
     * @param excludedTotal        판정에서 빠진 금액 (비과세 + 분리과세). 빠졌다는 사실을 숨기지 않는다
     * @param withheldTax          과세대상 행의 원천징수 합계 — 기납부세액으로 차감되는 값
     * @param excludedWithheldTax  비과세·분리과세 행의 원천징수 합계. 참고 표시용이며 기납부세액이 아니다
     */
    public record IncomeSummary(int year,
                                long interest,
                                long domesticDividend,
                                long foreignDividend,
                                long taxableTotal,
                                long excludedTotal,
                                long withheldTax,
                                long excludedWithheldTax,
                                List<Item> items) {
    }

    public record Item(String institution,
                       String accountNo,
                       String productName,
                       IncomeType type,
                       TaxTreatment treatment,
                       String source,
                       long amount,
                       long withheldTax) {
    }

    /** 기관 한 줄. {@code status} 는 지금은 CONFIRMED 뿐이다 — 조회 실패는 예외로 올라와 서비스가 보류한다. */
    public record InstitutionLine(String institution, String source, String status,
                                  int rows, long amount, long withheldTax) {
    }

    /**
     * @param lookupStatus    OK · NO_DATA(조회 성공, 자료 없음) · FAILED(조회 실패 — 금액은 모름)
     * @param taxableTotal    종합과세 판정에 들어가는 자사·연동 합계 (타사 제외)
     * @param excludedTotal   비과세·분리과세로 판정에서 빠진 금액
     * @param byType          과세대상 금액을 유형별로 (INTEREST / DOMESTIC_DIVIDEND / FOREIGN_DIVIDEND)
     * @param byTreatment     전 행을 과세구분별로 (TAXABLE / TAX_FREE / SEPARATE)
     * @param externalDeclared 고객이 말한 타사 금액. {@code null} = 미확인
     * @param externalStatus  UNKNOWN · DECLARED_ZERO · DECLARED
     * @param linkedInstitutions 연동으로 자료가 들어온 기관명 — 수기 입력과의 중복 확인에 쓴다
     * @param items           상품별 행. 계좌번호는 가려져 있다
     */
    public record IncomeOverview(int year,
                                 LocalDate asOf,
                                 String lookupStatus,
                                 long taxableTotal,
                                 long excludedTotal,
                                 List<InstitutionLine> institutions,
                                 Map<String, Long> byType,
                                 Map<String, Long> byTreatment,
                                 Long externalDeclared,
                                 String externalStatus,
                                 List<String> linkedInstitutions,
                                 List<Item> items,
                                 List<String> notes) {
    }
}
