package com.uniride.repositories;

import com.uniride.entities.Conductor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConductorRepository extends JpaRepository<Conductor, Long> {
}
