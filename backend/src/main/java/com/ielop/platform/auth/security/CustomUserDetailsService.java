package com.ielop.platform.auth.security;

import com.ielop.platform.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(AppUserPrincipal::new)
                // Deliberately generic message: do not reveal whether the
                // username exists (SR-04 / SR-06 adjacent — avoid
                // enumeration via error text).
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
    }
}
