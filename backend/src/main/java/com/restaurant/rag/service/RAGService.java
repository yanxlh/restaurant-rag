package com.restaurant.rag.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RAGService {

    private static final String SYSTEM_PROMPT = """
            你是一个餐厅评价分析助手。根据以下真实用户评价回答问题。
            只基于提供的评价内容作答，不要编造信息。
            如果评价中没有相关信息，请明确告知。
            请用简洁的中文回答，并在末尾注明参考了几条评价。
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final CacheService cacheService;

    public Flux<String> ask(String restaurantId, String question) {
        String cacheKey = restaurantId + ":" + md5(question);

        String cached = cacheService.get(cacheKey);
        if (cached != null) {
            return Flux.just(cached);
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .filterExpression(b.eq("restaurant_id", restaurantId).build())
                        .build()
        );

        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = "【餐厅评价】\n" + context + "\n\n【用户问题】\n" + question;

        StringBuilder fullAnswer = new StringBuilder();
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> {
                    String answer = fullAnswer.toString();
                    if (!answer.isBlank()) {
                        cacheService.set(cacheKey, answer);
                    }
                });
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.toLowerCase().trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
