package com.inwoo.taxdesk.web;

import com.inwoo.taxdesk.chat.AnswerDto;
import com.inwoo.taxdesk.chat.TaxDeskService;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 6 — 고객 상담 API.
 *
 * <p><b>{@code userId} 를 요청 본문에서 받지 않는다.</b> {@link Principal} 에서 꺼낸다.
 * 본문으로 받으면 "userId 를 richman 으로 보내면 되잖아"가 성립하고,
 * 그 순간 소유자 격리는 프롬프트 수준의 부탁이 된다.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final TaxDeskService service;

    public ChatController(TaxDeskService service) {
        this.service = service;
    }

    @PostMapping
    public AnswerDto ask(@RequestBody AskRequest request, Principal principal) {
        return service.ask(principal.getName(), scoped(principal, request.conversationId()),
                request.question(),
                request.otherIncome(), request.deduction(), request.prepaidTax());
    }

    /** 세션 대화 확인. 멀티턴이 실제로 이어지는지 눈으로 보는 용도다(실험 E5·E7). */
    @GetMapping("/history")
    public List<String> history(@RequestParam String conversationId, Principal principal) {
        return service.history(scoped(principal, conversationId));
    }

    @DeleteMapping("/history")
    public void clear(@RequestParam String conversationId, Principal principal) {
        service.clearHistory(scoped(principal, conversationId));
    }

    /**
     * <b>대화방 키에 소유자를 박는다.</b> 고객이 보낸 {@code conversationId} 를 그대로 쓰면
     * 서로 다른 계정이 같은 방에 들어간다 — 실제로 {@code demo-1} 하나에 세 계정의 금융소득이
     * 섞였고, 모델이 그 기억에서 <b>남의 배당금액</b>을 인용해 답했다.
     *
     * <p>도구 계층은 {@code findByOwnerIdAndYear} 로 소유자를 쿼리에 박아 두었는데,
     * 기억 계층과 조회 API 가 그 격리를 우회하고 있었다. <b>격리는 한 군데만 뚫려도 뚫린 것이다.</b>
     *
     * <p>조회·삭제도 같은 함수를 지나므로 남의 방을 지정할 방법 자체가 없다.
     * 권한을 검사해 거절하는 대신 <b>가리킬 수 없게</b> 만든다 — 검사에는 빠뜨릴 구멍이 있지만
     * 이름을 못 만드는 쪽에는 없다.
     */
    private static String scoped(Principal principal, String requested) {
        String room = requested == null || requested.isBlank() ? "default" : requested;
        return principal.getName() + ":" + room;
    }

    /**
     * @param otherIncome 이자·배당 외 종합소득금액. <b>문장 파싱보다 우선한다.</b>
     *                    "5천만"을 5,000,000 으로 잘못 읽으면 세액이 통째로 틀리는데,
     *                    입력란으로 받으면 그 실패 모드 자체가 사라진다. {@code null} 이면 미상
     * @param deduction   종합소득공제 합계. {@code null} 이면 앞 턴 값 유지
     * @param prepaidTax  근로·사업 기납부세액. {@code null} 이면 앞 턴 값 유지
     */
    public record AskRequest(@NotBlank String question, String conversationId,
                             Long otherIncome, Long deduction, Long prepaidTax) {
    }
}
