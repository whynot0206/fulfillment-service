package com.why.fulfillment.commerce.auth.service;

import com.why.fulfillment.commerce.auth.JwtCodec;
import com.why.fulfillment.commerce.auth.dto.AuthTokenView;
import com.why.fulfillment.commerce.auth.dto.LoginRequest;
import com.why.fulfillment.commerce.auth.dto.RegisterRequest;
import com.why.fulfillment.commerce.auth.entity.UserAccount;
import com.why.fulfillment.commerce.auth.mapper.UserAccountMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** 用户正常。与 user_account.status 取值一致。 */
    private static final int STATUS_ACTIVE = 1;

    /**
     * 用户不存在时用来消耗时间的假摘要，对应密码 "placeholder-never-matches"。
     *
     * <p>不这么做的话，「用户不存在」会立刻返回、「密码错误」要跑完一次 BCrypt，
     * 响应时间差足以让人枚举出哪些用户名已注册。</p>
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtCodec jwtCodec;

    public AuthService(UserAccountMapper userAccountMapper,
                       PasswordEncoder passwordEncoder,
                       JwtCodec jwtCodec) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtCodec = jwtCodec;
    }

    @Transactional
    public AuthTokenView register(RegisterRequest request) {
        UserAccount account = new UserAccount();
        account.setUsername(request.username());
        account.setPasswordHash(passwordEncoder.encode(request.password()));

        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException exception) {
            // 唯一键是判重的唯一依据，不在插入前查一次。
            throw CommerceException.conflict("USERNAME_TAKEN", "用户名已被占用");
        }
        return issue(account);
    }

    public AuthTokenView login(LoginRequest request) {
        UserAccount account = userAccountMapper.selectByUsername(request.username());

        String hash = account == null ? DUMMY_HASH : account.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        // 三种失败（用户不存在 / 密码错 / 账号禁用）回同一个错误码。
        // 区分开等于免费告诉攻击者「这个用户名是存在的」。
        if (account == null || !passwordMatches || !isActive(account)) {
            throw CommerceException.unauthorized("BAD_CREDENTIALS", "用户名或密码错误");
        }
        return issue(account);
    }

    public UserAccount requireActive(long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || !isActive(account)) {
            throw CommerceException.unauthorized("USER_UNAVAILABLE", "登录状态已失效，请重新登录");
        }
        return account;
    }

    private AuthTokenView issue(UserAccount account) {
        String token = jwtCodec.issue(account.getId(), account.getUsername());
        return new AuthTokenView(token, jwtCodec.ttlSeconds(), account.getId(), account.getUsername());
    }

    private static boolean isActive(UserAccount account) {
        return account.getStatus() != null && account.getStatus() == STATUS_ACTIVE;
    }
}
