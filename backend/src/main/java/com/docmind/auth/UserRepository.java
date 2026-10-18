package com.docmind.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcClient jdbc;

    public Optional<SysUser> findByUsername(String username) {
        return jdbc.sql("SELECT id, username, password_hash, display_name, role, status, created_at, last_login_at "
                        + "FROM sys_user WHERE username = ?")
                .param(username)
                .query(this::map)
                .optional();
    }

    public Optional<SysUser> findById(long id) {
        return jdbc.sql("SELECT id, username, password_hash, display_name, role, status, created_at, last_login_at "
                        + "FROM sys_user WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    public long insert(String username, String passwordHash, String displayName, String role) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO sys_user (username, password_hash, display_name, role, status, created_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?)")
                .params(username, passwordHash, displayName, role, Timestamp.from(Instant.now()))
                .update(holder, "id");
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void touchLogin(long id) {
        jdbc.sql("UPDATE sys_user SET last_login_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now()), id)
                .update();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM sys_user").query(Long.class).single();
    }

    private SysUser map(java.sql.ResultSet rs, int row) {
        try {
            return new SysUser(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("display_name"),
                    rs.getString("role"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant());
        } catch (Exception e) {
            throw new IllegalStateException("用户映射失败", e);
        }
    }
}
