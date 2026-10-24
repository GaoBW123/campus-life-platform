package com.campus.life;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.campus.life.mapper")
@SpringBootApplication
@EnableScheduling
public class CampusLifeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusLifeApplication.class, args);
    }

}
