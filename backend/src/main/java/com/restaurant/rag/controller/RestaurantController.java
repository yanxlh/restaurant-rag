package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantRepository restaurantRepository;

    @GetMapping
    public List<Restaurant> search(
            @RequestParam String city,
            @RequestParam(defaultValue = "") String name) {
        if (name.isBlank()) {
            return restaurantRepository.findByCityIgnoreCase(city);
        }
        return restaurantRepository.findByCityIgnoreCaseAndNameContainingIgnoreCase(city, name);
    }
}
