package com.chaosledger.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

	static {
		// Force UTC timezone to avoid "Asia/Calcutta" rejection by PostgreSQL
		System.setProperty("user.timezone", "UTC");
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(LedgerApplication.class, args);
	}
}