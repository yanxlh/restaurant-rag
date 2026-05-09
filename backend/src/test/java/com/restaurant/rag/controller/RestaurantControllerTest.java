package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.when;

@WebFluxTest(RestaurantController.class)
class RestaurantControllerTest {

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private RestaurantRepository restaurantRepository;

    @Test
    void search_by_city_returns_restaurant_list() {
        Restaurant r = new Restaurant();
        r.setId("biz1");
        r.setName("Golden Dragon");
        r.setCity("Las Vegas");
        when(restaurantRepository.findByCityIgnoreCase("Las Vegas")).thenReturn(List.of(r));

        webClient.get().uri("/api/restaurants?city=Las Vegas")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Restaurant.class)
                .hasSize(1);
    }

    @Test
    void search_with_name_filter_calls_name_query() {
        when(restaurantRepository.findByCityIgnoreCaseAndNameContainingIgnoreCase("Las Vegas", "Dragon"))
                .thenReturn(List.of());

        webClient.get().uri("/api/restaurants?city=Las Vegas&name=Dragon")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Restaurant.class)
                .hasSize(0);
    }
}
