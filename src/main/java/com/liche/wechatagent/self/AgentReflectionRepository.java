package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentReflectionRepository extends JpaRepository<AgentReflection, Long> {

    List<AgentReflection> findTop50ByOrderByIdDesc();
}
