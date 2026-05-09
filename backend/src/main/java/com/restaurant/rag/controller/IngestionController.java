package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/restaurants")
    public Map<String, Integer> ingestRestaurants(@RequestBody List<Restaurant> restaurants) {
        int count = ingestionService.saveRestaurants(restaurants);
        return Map.of("imported", count);
    }
}
