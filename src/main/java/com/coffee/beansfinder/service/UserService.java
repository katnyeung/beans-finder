package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.User;
import com.coffee.beansfinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /**
     * Find or create user from Google OAuth2 response
     */
    @Transactional
    public User findOrCreateUser(OAuth2User oAuth2User) {
        String googleId = oAuth2User.getAttribute("sub");
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String pictureUrl = oAuth2User.getAttribute("picture");

        Optional<User> existingUser = userRepository.findByGoogleId(googleId);

        if (existingUser.isPresent()) {
            // Update last login and potentially updated info
            User user = existingUser.get();
            user.setLastLoginAt(LocalDateTime.now());
            user.setName(name);
            user.setPictureUrl(pictureUrl);
            log.info("User logged in: {} ({})", email, googleId);
            return userRepository.save(user);
        } else {
            // Create new user
            User newUser = User.builder()
                    .googleId(googleId)
                    .email(email)
                    .name(name)
                    .pictureUrl(pictureUrl)
                    .build();
            log.info("New user registered: {} ({})", email, googleId);
            return userRepository.save(newUser);
        }
    }

    public Optional<User> findByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
