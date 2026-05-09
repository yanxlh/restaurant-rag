package com.restaurant.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class RAGServiceTest {

    @Autowired
    private RAGService ragService;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private ChatClient chatClient;

    @Test
    void ask_returns_cached_answer_without_hitting_vector_store() {
        when(cacheService.get(any())).thenReturn("cached answer");

        StepVerifier.create(ragService.ask("biz1", "is it spicy?"))
                .expectNext("cached answer")
                .verifyComplete();

        verifyNoInteractions(vectorStore);
    }

    @Test
    void ask_on_cache_miss_queries_vector_store_and_streams_tokens() {
        when(cacheService.get(any())).thenReturn(null);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("Great spicy noodles!", java.util.Map.of())));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec     = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Yes", " it", " is", " spicy."));

        StepVerifier.create(ragService.ask("biz1", "is it spicy?"))
                .expectNext("Yes", " it", " is", " spicy.")
                .verifyComplete();

        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
