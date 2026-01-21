package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedditPost {

    private String id;
    private String title;
    private String selftext;
    private String author;
    private String subreddit;
    private int score;
    private int numComments;
    private String url;
    private String permalink;
    private LocalDateTime createdAt;

    // Top comments from the discussion
    private List<RedditComment> topComments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedditComment {
        private String author;
        private String body;
        private int score;
    }
}
