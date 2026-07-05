package com.sang.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI spotlinkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SpotLink 接口文档")
                        .description("SpotLink — 高并发社交电商平台：商铺发现、秒杀优惠券、用户签到、博客社交。"
                                + "\\n\\n**认证方式**：登录后返回 token，在请求头 `Authorization` 中携带。")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("SpotLink Team")
                                .email("dev@spotlink.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("全部接口")
                .pathsToMatch("/**")
                .build();
    }

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("用户模块")
                .pathsToMatch("/user/**")
                .build();
    }

    @Bean
    public GroupedOpenApi shopApi() {
        return GroupedOpenApi.builder()
                .group("商铺模块")
                .pathsToMatch("/shop/**", "/shop-type/**")
                .build();
    }

    @Bean
    public GroupedOpenApi voucherApi() {
        return GroupedOpenApi.builder()
                .group("优惠券 & 秒杀")
                .pathsToMatch("/voucher/**", "/voucher-order/**")
                .build();
    }

    @Bean
    public GroupedOpenApi blogApi() {
        return GroupedOpenApi.builder()
                .group("博客社交模块")
                .pathsToMatch("/blog/**", "/blog-comments/**", "/follow/**")
                .build();
    }

    @Bean
    public GroupedOpenApi uploadApi() {
        return GroupedOpenApi.builder()
                .group("文件上传")
                .pathsToMatch("/upload/**")
                .build();
    }
}
