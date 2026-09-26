package com.why.fulfillment.commerce;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Commerce 模块化单体：用户、商品、购物车、结算入口。
 *
 * <p>它不拥有库存与订单事实——可售库存来自 inventory-service，订单状态来自
 * order-service。本服务只拥有展示用商品数据、用户账号和购物车。</p>
 */
@SpringBootApplication
@MapperScan("com.why.fulfillment.commerce.**.mapper")
@EnableFeignClients(basePackages = "com.why.fulfillment.api")
@EnableScheduling
public class CommerceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceServiceApplication.class, args);
    }
}
