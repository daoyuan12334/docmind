package com.docmind.security;

/** 当前登录用户的线程上下文，供 Service 层取用（避免层层传参）。 */
public final class CurrentUser {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    static void set(AuthUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }

    public static AuthUser get() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new com.docmind.common.BizException(com.docmind.common.ErrorCode.UNAUTHORIZED, "未登录");
        }
        return user;
    }

    public static AuthUser getOrNull() {
        return HOLDER.get();
    }

    public static long id() {
        return get().userId();
    }

    public static boolean isAdmin() {
        AuthUser u = HOLDER.get();
        return u != null && u.isAdmin();
    }
}
