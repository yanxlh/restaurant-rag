package com.restaurant.rag.model;

import lombok.Data;

@Data
public class AskRequest {
    private String restaurantId;
    private String question;
}
