package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.UserDTO;
import com.coffee.beansfinder.entity.User;
import com.coffee.beansfinder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication endpoints")
public class AuthController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Returns the currently logged in user, or null if not authenticated")
    public ResponseEntity<UserDTO> getCurrentUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.ok(null);
        }

        String googleId = principal.getAttribute("sub");
        Optional<User> user = userService.findByGoogleId(googleId);

        return user.map(u -> ResponseEntity.ok(UserDTO.fromEntity(u)))
                .orElse(ResponseEntity.ok(null));
    }
}
