package com.inwoo.taxdesk.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * 임베딩 없이 도는 벡터 스토어 대역.
 *
 * <p>검색은 <b>낱말 겹침</b>으로 한다 — 질문의 2글자 이상 토큰이 본문에 들어 있는 문서를 돌려준다.
 * 의미 검색을 흉내 내려는 것이 아니라 "서비스가 검색 결과를 어떻게 다루는가"를 검증하기 위한 것이다.
 * {@code failNext} 를 켜면 다음 검색이 예외를 던진다 (검색 장애 시나리오).
 */
public class FakeVectorStore implements VectorStore {

    public final Map<String, Document> docs = new LinkedHashMap<>();
    public final List<SearchRequest> requests = new ArrayList<>();
    public boolean failNext;
    public int deleteCalls;

    @Override
    public void add(List<Document> documents) {
        documents.forEach(d -> docs.put(d.getId(), d));
    }

    @Override
    public void delete(List<String> idList) {
        deleteCalls++;
        idList.forEach(docs::remove);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("not needed in tests");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        requests.add(request);
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("embedding service down");
        }
        List<Document> out = new ArrayList<>();
        String[] tokens = request.getQuery().split("[\\s?.,!]+");
        for (Document d : docs.values()) {
            String text = d.getText() == null ? "" : d.getText();
            boolean hit = false;
            for (String t : tokens) {
                if (t.length() >= 2 && text.contains(t)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                out.add(d.mutate().score(0.5).build());
            }
            if (out.size() >= request.getTopK()) {
                break;
            }
        }
        return out;
    }
}
