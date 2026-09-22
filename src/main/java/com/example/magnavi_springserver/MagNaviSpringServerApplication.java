package com.example.magnavi_springserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 업무 모듈과 공통 설정을 한 Spring 애플리케이션으로 실행한다. */
@SpringBootApplication
public class MagNaviSpringServerApplication {

    /** 활성 프로필과 외부 설정을 읽어 서버를 시작한다. */
    public static void main(String[] args) {
        SpringApplication.run(MagNaviSpringServerApplication.class, args);
    }

}
