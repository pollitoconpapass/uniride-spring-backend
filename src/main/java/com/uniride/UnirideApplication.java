package com.uniride;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UnirideApplication {
	public static void main(String[] args) {
		SpringApplication.run(UnirideApplication.class, args);
	}
}
