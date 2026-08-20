package com.inwoo.taxdesk.tools;

import com.inwoo.taxdesk.domain.ApplicationStatus;
import com.inwoo.taxdesk.domain.ProductApplication;
import com.inwoo.taxdesk.domain.TaxSaverProduct;
import com.inwoo.taxdesk.repository.ProductApplicationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 4·7 — 절세상품 가입 <b>자격 확인</b> 신청. 이 실습의 승인 게이트다.
 *
 * <p>"세금 줄일 방법 없나요?"는 이 서비스에 반드시 들어오는 질문이고, 증권사는 답할 자격이 있다.
 * 세무대리는 못 해도(세무사법 §2) <b>자사 상품의 세제 혜택 안내는 본업</b>이기 때문이다.
 *
 * <p><b>도구는 {@code PENDING} 까지만 만든다.</b> 이유가 설계 취향이 아니라 법에 있다 —
 * 조특법 §88의2③ 은 "가입대상의 확인"을 별도 절차로 두고, 자격 요건은 전부
 * 서류(기초연금 수급 증명 · 장애인 등록증 · 소득확인증명서 …)로 확인해야 한다.
 * 챗봇은 그 서류를 볼 수 없다.
 *
 * <p><b>거절이 정상 경로다.</b> 비과세종합저축 자격은 열거주의(§88의2① 각 호)라
 * 해당하지 않는 고객이 훨씬 많다. 승인만 있고 거절이 없는 게이트는 게이트가 아니라 결재 도장이다.
 *
 * <p>⚠️ 응답에 <b>투자권유가 섞이면 안 된다.</b> 세무사법과 별개로 자본시장법이 거는 제약이다.
 */
@Component
public class ProductApplicationTools {

    private final ProductApplicationRepository repository;

    public ProductApplicationTools(ProductApplicationRepository repository) {
        this.repository = repository;
    }

    @Tool(description = """
            절세상품(비과세종합저축 TAX_FREE_SAVINGS, ISA 서민형 ISA_LOW_INCOME)의
            가입 자격 확인을 신청한다. 접수만 하며 가입을 확정하지 않는다.
            자격 판정은 담당자가 서류로 하므로 이 도구는 결과를 알려줄 수 없다.""")
    public ApplicationReceipt requestEligibilityCheck(
            @ToolParam(description = "TAX_FREE_SAVINGS 또는 ISA_LOW_INCOME") TaxSaverProduct product,
            @ToolParam(description = "고객이 말한 신청 사유 요약") String reason,
            @ToolParam(description = "직전에 안내한 예상 결정세액. 없으면 0") long estimatedTax,
            ToolContext context) {

        String ownerId = ToolUser.of(context);
        ProductApplication saved = repository.save(new ProductApplication(
                UUID.randomUUID().toString().substring(0, 8),
                ownerId, product, reason, estimatedTax, LocalDateTime.now()));

        return new ApplicationReceipt(saved.getId(), product, saved.getStatus(),
                """
                접수되었습니다. 가입 자격은 담당자가 서류로 확인한 뒤 안내드립니다.
                (근거: 조세특례제한법 제88조의2 제3항 — 가입대상의 확인)""");
    }

    @Tool(description = "로그인한 고객 본인이 신청한 자격 확인 건의 진행 상태를 조회한다.")
    public List<ApplicationView> listMyApplications(ToolContext context) {
        String ownerId = ToolUser.of(context);
        return repository.findByOwnerIdOrderByRequestedAtDesc(ownerId).stream()
                .map(a -> new ApplicationView(a.getId(), a.getProduct(), a.getStatus(),
                        a.getDecisionNote(), a.getRequestedAt()))
                .toList();
    }

    // 상태 변경 도구는 만들지 않는다. 만드는 순간 모델이 승인할 수 있게 되고 게이트가 무의미해진다.
    // APPROVED / REJECTED 로 가는 길은 AdminController 뿐이다.

    public record ApplicationReceipt(String id, TaxSaverProduct product,
                                     ApplicationStatus status, String note) {
    }

    public record ApplicationView(String id, TaxSaverProduct product, ApplicationStatus status,
                                  String decisionNote, LocalDateTime requestedAt) {
    }
}
