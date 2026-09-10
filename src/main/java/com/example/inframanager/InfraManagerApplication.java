package com.example.inframanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InfraManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfraManagerApplication.class, args);
    }

}
