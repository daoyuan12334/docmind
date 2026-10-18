package com.docmind.auth;

import com.docmind.common.ApiResult;
import com.docmind.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    public record RegisterRequest(@NotBlank String username,
                                  @Size(min = 6) String password,
                                  String displayName) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long userId, String username, String role) {
    }

    @PostMapping("/register")
    public ApiResult<?> register(@RequestBody @Validated RegisterRequest req) {
        SysUser user = authService.register(req.username(), req.password(), req.displayName());
        return ApiResult.ok(Map.of("userId", user.id(), "role", user.role()));
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@RequestBody @Validated LoginRequest req) {
        var result = authService.login(req.username(), req.password());
        SysUser user = result.user();
        return ApiResult.ok(new LoginResponse(result.token(), "Bearer", user.id(), user.username(), user.role()));
    }

    @GetMapping("/me")
    public ApiResult<?> me() {
        SysUser user = authService.current(CurrentUser.id());
        return ApiResult.ok(Map.of(
                "userId", user.id(),
                "username", user.username(),
                "displayName", user.displayName(),
                "role", user.role()));
    }
}
