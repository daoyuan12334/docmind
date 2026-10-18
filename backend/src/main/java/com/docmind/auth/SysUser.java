package com.docmind.auth;

import java.time.Instant;

/** 系统用户。角色：ADMIN（全库运维）/ MEMBER（普通成员）。 */
public record SysUser(Long id,
                      String username,
                      String passwordHash,
                      String displayName,
                      String role,
                      String status,
                      Instant createdAt,
                      Instant lastLoginAt) {
}
