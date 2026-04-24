package com.ems.app.init;

import com.ems.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private final UserRepository users;

    public AdminInitializer(UserRepository u) { this.users = u; }

    @Override
    public void run(String... args) {
        if (users.findByUsername("admin").isEmpty()) {
            log.warn("ADMIN ACCOUNT NOT FOUND. Seed migration may have been skipped. " +
                "Run Flyway manually or insert admin via SQL.");
        } else {
            log.info("admin account verified OK");
        }
    }
}
