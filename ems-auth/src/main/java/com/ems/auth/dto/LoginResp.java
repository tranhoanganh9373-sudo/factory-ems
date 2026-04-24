package com.ems.auth.dto;
public record LoginResp(String accessToken, long expiresInSeconds, UserInfo user) {
    public record UserInfo(Long id, String username, String displayName, java.util.List<String> roles) {}
}
