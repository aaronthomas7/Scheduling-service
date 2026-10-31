package com.example.demo.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.demo.model.Host;
import com.example.demo.repository.HostRepository;

/** Hosts are prefilled rows; in the local profile this stands in for that, so the APIs have someone to work on. */
@Component
@Profile("local")
public class LocalSeedData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedData.class);

    private final HostRepository hosts;

    public LocalSeedData(HostRepository hosts) {
        this.hosts = hosts;
    }

    @Override
    public void run(String... args) {
        if (hosts.count() > 0) {
            return;
        }
        hosts.saveAll(List.of(
                host(1L, "Priya Sharma", "priya@example.com", "Asia/Kolkata"),
                host(2L, "James Carter", "james@example.com", "Europe/London"),
                host(3L, "Maria Lopez", "maria@example.com", "America/New_York")));
        log.info("Local profile: seeded hosts 1-3 with no availability yet. Publish some with POST /publish-availability");
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
}
