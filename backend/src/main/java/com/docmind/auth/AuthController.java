package com.docmind.auth;

import com.docmind.common.ApiResult;
import com.docmind.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "认证与账号", description = "注册 / 登录 / 当前登录人。登录返回的 token 用于其他接口的 Bearer 鉴权。")
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

    @Operation(summary = "注册账号",
            description = "用户名唯一，密码至少 6 位。首个注册的用户会被自动授予 ROLE_ADMIN。")
    @PostMapping("/register")
    public ApiResult<?> register(@RequestBody @Validated RegisterRequest req) {
        SysUser user = authService.register(req.username(), req.password(), req.displayName());
        return ApiResult.ok(Map.of("userId", user.id(), "role", user.role()));
    }

    @Operation(summary = "登录获取 JWT",
            description = "成功返回 token / tokenType=Bearer，后续请求用 Authorization: Bearer <token>。")
    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@RequestBody @Validated LoginRequest req) {
        var result = authService.login(req.username(), req.password());
        SysUser user = result.user();
        return ApiResult.ok(new LoginResponse(result.token(), "Bearer", user.id(), user.username(), user.role()));
    }

    @Operation(summary = "当前登录人信息")
    @SecurityRequirement(name = "bearer-jwt")
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
