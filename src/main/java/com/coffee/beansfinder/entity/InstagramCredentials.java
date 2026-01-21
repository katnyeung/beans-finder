package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "instagram_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramCredentials {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instagram_user_id", nullable = false, unique = true, length = 50)
    private String instagramUserId;

    @Column(length = 100)
    private String username;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(tokenExpiresAt);
    }
}
