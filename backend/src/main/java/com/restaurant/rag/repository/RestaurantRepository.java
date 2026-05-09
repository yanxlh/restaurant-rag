package com.restaurant.rag.repository;

import com.restaurant.rag.model.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestaurantRepository extends JpaRepository<Restaurant, String> {
    List<Restaurant> findByCityIgnoreCase(String city);
    List<Restaurant> findByCityIgnoreCaseAndNameContainingIgnoreCase(String city, String name);
}
