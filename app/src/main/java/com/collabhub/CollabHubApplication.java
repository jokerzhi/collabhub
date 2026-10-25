package com.collabhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CollabHub 启动类。
 *
 * <p>唯一的部署单元。业务代码全部在 {@code com.collabhub.*} 的模块包里，
 * 这里什么都不放 —— 启动类一旦开始写业务，模块边界就守不住了。
 */
@SpringBootApplication
public class CollabHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabHubApplication.class, args);
    }
}
