package com.ems.auth.service;

import com.ems.auth.dto.*;

public interface AuthService {
    /** Login, returns access token + refresh token */
    LoginResult login(String username, String password, String ip, String userAgent);
    LoginResult refresh(String refreshToken, String ip, String userAgent);
    void logout(String refreshToken);

    record LoginResult(String accessToken, long accessExpSeconds,
                       String refreshToken, long refreshMaxAgeSeconds,
                       LoginResp.UserInfo user) {}
}
