package com.example.inframanager.deployment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRecordRepository extends JpaRepository<DeploymentRecord, Long> {

    Optional<DeploymentRecord> findByBambooDeploymentResultId(long bambooDeploymentResultId);
}
