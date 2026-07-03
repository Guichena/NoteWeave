package com.noteweave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NoteWeaveApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoteWeaveApplication.class, args);
    }
}
