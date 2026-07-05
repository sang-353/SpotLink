package com.sang;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.sang.mapper")
@SpringBootApplication
public class SpotLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotLinkApplication.class, args);
    }

}
