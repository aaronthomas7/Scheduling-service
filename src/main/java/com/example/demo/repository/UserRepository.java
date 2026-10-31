package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.model.AppUser;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    List<AppUser> findByHostId(Long hostId);
}
