package com.restaurant.rag.service;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IngestionService {

    private final RestaurantRepository restaurantRepository;

    public int saveRestaurants(List<Restaurant> restaurants) {
        restaurantRepository.saveAll(restaurants);
        return restaurants.size();
    }
}
