package com.mouse.telecommvendorai;

import org.springframework.boot.SpringApplication;

public class TestTelecommVendorAiApplication {

    public static void main(String[] args) {
        SpringApplication.from(TelecommVendorAiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
