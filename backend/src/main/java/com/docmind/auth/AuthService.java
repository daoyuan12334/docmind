package com.docmind.auth;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    /** 注册。首个账号自动成为管理员，避免部署后无管理员可用。 */
    public SysUser register(String username, String password, String displayName) {
        if (!StringUtils.hasText(username) || username.length() < 3 || username.length() > 32) {
            throw BizException.badRequest("用户名长度需为 3-32 位");
        }
        if (!StringUtils.hasText(password) || password.length() < 6) {
            throw BizException.badRequest("密码至少 6 位");
        }
        users.findByUsername(username).ifPresent(u -> {
            throw BizException.badRequest("用户名已存在");
        });
        boolean firstUser = users.count() == 0;
        String role = firstUser ? "ADMIN" : "MEMBER";
        long id = users.insert(username, encoder.encode(password),
                StringUtils.hasText(displayName) ? displayName : username, role);
        return users.findById(id).orElseThrow(() -> BizException.notFound("用户"));
    }

    public record LoginResult(String token, SysUser user) {
    }

    public LoginResult login(String username, String password) {
        SysUser user = users.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (!"ACTIVE".equalsIgnoreCase(user.status())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (!encoder.matches(password, user.passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        users.touchLogin(user.id());
        String token = jwtService.issue(user.id(), user.username(), user.role());
        return new LoginResult(token, user);
    }

    public SysUser current(long userId) {
        return users.findById(userId).orElseThrow(() -> BizException.notFound("用户"));
    }
}
