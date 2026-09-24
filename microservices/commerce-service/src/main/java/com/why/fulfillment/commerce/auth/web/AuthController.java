package com.why.fulfillment.commerce.auth.web;

import com.why.fulfillment.commerce.auth.CurrentUser;
import com.why.fulfillment.commerce.auth.JwtPrincipal;
import com.why.fulfillment.commerce.auth.dto.AuthTokenView;
import com.why.fulfillment.commerce.auth.dto.LoginRequest;
import com.why.fulfillment.commerce.auth.dto.RegisterRequest;
import com.why.fulfillment.commerce.auth.entity.UserAccount;
import com.why.fulfillment.commerce.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthTokenView> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenView> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * 当前登录用户。
     *
     * <p>每次都回查一次库而不是直接用令牌里的 name：令牌签发后用户可能已被禁用，
     * 而令牌本身在过期前一直有效。这是无状态令牌的固有代价——要么查库，
     * 要么接受「禁用后最多还能用 ttl 秒」。</p>
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@CurrentUser JwtPrincipal principal) {
        UserAccount account = authService.requireActive(principal.userId());
        return ResponseEntity.ok(Map.of(
                "userId", account.getId(),
                "username", account.getUsername()));
    }
}
