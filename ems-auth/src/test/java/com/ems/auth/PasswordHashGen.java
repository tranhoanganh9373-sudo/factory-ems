package com.ems.auth;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGen {
    public static void main(String[] args) {
        System.out.println(new BCryptPasswordEncoder(12).encode("admin123!"));
    }
}
