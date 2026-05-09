package com.restaurant.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "restaurants")
@Data
@NoArgsConstructor
public class Restaurant {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    private String city;
    private String state;
    private Double stars;
    private Integer reviewCount;

    @Column(length = 500)
    private String categories;
}
