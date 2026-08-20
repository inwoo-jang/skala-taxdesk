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
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? principal.getName() + "-default"
                : request.conversationId();
        return service.ask(principal.getName(), conversationId, request.question());
    }

    /** 세션 대화 확인. 멀티턴이 실제로 이어지는지 눈으로 보는 용도다(실험 E5·E7). */
    @GetMapping("/history")
    public List<String> history(@RequestParam String conversationId) {
        return service.history(conversationId);
    }

    @DeleteMapping("/history")
    public void clear(@RequestParam String conversationId) {
        service.clearHistory(conversationId);
    }

    public record AskRequest(@NotBlank String question, String conversationId) {
    }
}
