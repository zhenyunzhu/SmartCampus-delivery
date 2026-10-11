package com.smartcampus.utils;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 工具类单元测试
 */
class JwtUtilTest {

    private static final String ADMIN_SECRET = "scd-admin-test-secret";
    private static final String USER_SECRET = "scd-user-test-secret";

    @Test
    @DisplayName("签发的令牌可以被正确解析，且携带业务声明")
    void shouldParseClaimsFromGeneratedToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("empId", 1L);

        String token = JwtUtil.createJWT(ADMIN_SECRET, 60_000L, claims);
        assertNotNull(token);

        Claims parsed = JwtUtil.parseJWT(ADMIN_SECRET, token);
        assertEquals("1", parsed.get("empId").toString());
    }

    @Test
    @DisplayName("使用错误的密钥解析令牌应失败，保证管理端与用户端 Token 相互隔离")
    void shouldRejectTokenParsedWithWrongSecret() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", 9L);

        // 用用户端密钥签发
        String token = JwtUtil.createJWT(USER_SECRET, 60_000L, claims);

        // 用管理端密钥解析必须失败
        assertThrows(Exception.class, () -> JwtUtil.parseJWT(ADMIN_SECRET, token));
    }

    @Test
    @DisplayName("已过期的令牌应解析失败")
    void shouldRejectExpiredToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("empId", 1L);

        // 过期时间设置为过去
        String token = JwtUtil.createJWT(ADMIN_SECRET, -1_000L, claims);

        assertThrows(Exception.class, () -> JwtUtil.parseJWT(ADMIN_SECRET, token));
    }

    @Test
    @DisplayName("被篡改的令牌应解析失败")
    void shouldRejectTamperedToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("empId", 1L);
        String token = JwtUtil.createJWT(ADMIN_SECRET, 60_000L, claims);

        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThrows(Exception.class, () -> JwtUtil.parseJWT(ADMIN_SECRET, tampered));
    }
}
