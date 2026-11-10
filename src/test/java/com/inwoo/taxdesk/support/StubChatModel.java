package com.inwoo.taxdesk.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 네트워크 없이 도는 모델 대역. <b>API 키 없이 서비스 흐름 전체를 테스트하기 위한 장치다.</b>
 *
 * <p>프롬프트를 받아 문자열을 돌려주는 함수 하나로 동작을 정한다. 받은 프롬프트는 전부 기록하므로
 * "모델이 호출됐는가 · 몇 번 · 어떤 컨텍스트로"를 테스트가 검증할 수 있다.
 * 토큰 사용량은 호출당 고정값(prompt 10 · completion 5)으로 채운다 — 0 과 구분만 되면 된다.
 */
public class StubChatModel implements ChatModel {

    public final List<Prompt> prompts = new ArrayList<>();
    private Function<Prompt, String> reply;
    private RuntimeException failure;

    public StubChatModel(String fixedReply) {
        this.reply = p -> fixedReply;
    }

    public StubChatModel(Function<Prompt, String> reply) {
        this.reply = reply;
    }

    public static StubChatModel failing(RuntimeException e) {
        StubChatModel m = new StubChatModel("");
        m.failure = e;
        return m;
    }

    public StubChatModel reply(String text) {
        this.reply = p -> text;
        return this;
    }

    public StubChatModel reply(Function<Prompt, String> f) {
        this.reply = f;
        return this;
    }

    public int calls() {
        return prompts.size();
    }

    public Prompt lastPrompt() {
        return prompts.isEmpty() ? null : prompts.get(prompts.size() - 1);
    }

    /** 마지막 호출의 system 메시지 전문. 서비스가 조립한 컨텍스트를 검사할 때 쓴다. */
    public String lastSystemText() {
        Prompt p = lastPrompt();
        if (p == null) {
            return "";
        }
        return p.getInstructions().stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .map(m -> m.getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (failure != null) {
            throw failure;
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(reply.apply(prompt)))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build())
                .build();
    }
}
