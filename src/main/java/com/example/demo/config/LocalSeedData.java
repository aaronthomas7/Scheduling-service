package com.example.demo.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.demo.model.AppUser;
import com.example.demo.model.Host;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.UserRepository;

/**
 * Hosts and users (invitees) are prefilled rows; in the local profile this stands in for that, so the
 * APIs and pages have someone to work with.
 */
@Component
@Profile("local")
public class LocalSeedData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedData.class);

    private final HostRepository hosts;
    private final UserRepository users;

    public LocalSeedData(HostRepository hosts, UserRepository users) {
        this.hosts = hosts;
        this.users = users;
    }

    @Override
    public void run(String... args) {
        // "local" is the default profile, so make it obvious when a real deployment forgot to pick MySQL.
        log.warn("Running the local profile: in-memory H2, data is lost on stop. "
                + "Set SPRING_PROFILES_ACTIVE=mysql to use MySQL.");
        if (hosts.count() == 0) {
            hosts.saveAll(List.of(
                    host(1L, "Priya Sharma", "priya@example.com", "Asia/Kolkata"),
                    host(2L, "James Carter", "james@example.com", "Europe/London"),
                    host(3L, "Maria Lopez", "maria@example.com", "America/New_York")));
            log.info("Local profile: seeded hosts 1-3 with no availability yet. Publish some at /host.html");
        }
        if (users.count() == 0) {
            users.saveAll(List.of(
                    user(1L, "Alex Kim", "alex@example.com"),
                    user(2L, "Sam Patel", "sam@example.com"),
                    user(3L, "Jordan Lee", "jordan@example.com"),
                    user(4L, "Taylor Brooks", "taylor@example.com"),
                    user(5L, "Riya Mehta", "riya@example.com")));
            log.info("Local profile: seeded invited users alex@, sam@, jordan@, taylor@, riya@example.com. "
                    + "Book at /book.html?hosts=1");
        }
    }

    private static Host host(Long id, String name, String email, String timezone) {
        Host h = new Host();
        h.setId(id);
        h.setName(name);
        h.setEmail(email);
        h.setTimezone(timezone);
        h.setAvailability(List.of());
        return h;
    }

    private static AppUser user(Long id, String name, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setName(name);
        u.setEmail(email);
        return u;
    }
}
