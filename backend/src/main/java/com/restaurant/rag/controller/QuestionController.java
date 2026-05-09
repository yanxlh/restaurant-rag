package com.restaurant.rag.controller;

import com.restaurant.rag.model.AskRequest;
import com.restaurant.rag.service.RAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QuestionController {

    private final RAGService ragService;

    /**
     * SSE 流式问答接口。
     * 前端使用 fetch + ReadableStream 接收（不用 EventSource，因其不支持 POST）。
     */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestBody AskRequest request) {
        return ragService.ask(request.getRestaurantId(), request.getQuestion());
    }
}
