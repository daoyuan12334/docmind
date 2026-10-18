package com.docmind.observe;

import com.docmind.common.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Instant;

/** 审计日志：记录谁在什么时候对什么资源做了什么，满足企业合规留痕要求。 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcClient jdbc;

    public void record(String action, String resource, String detail) {
        Long userId = null;
        String username = null;
        var current = com.docmind.security.CurrentUser.getOrNull();
        if (current != null) {
            userId = current.userId();
            username = current.getUsername();
        }
        String ip = null;
        String ua = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            HttpServletRequest req = sa.getRequest();
            ip = req.getRemoteAddr();
            ua = req.getHeader("User-Agent");
        }
        jdbc.sql("INSERT INTO audit_log (user_id, username, action, resource, detail, ip, ua, trace_id, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")
                .params(userId, username, action, resource, detail, ip,
                        ua == null ? null : (ua.length() > 255 ? ua.substring(0, 255) : ua),
                        TraceContext.current(), Timestamp.from(Instant.now()))
                .update();
    }
}
