package com.example.magnavi_springserver.member.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.magnavi_springserver.member.domain.Member;

/** 회원 프로필을 저장·조회하는 member 내부 저장소다. 다른 모듈에 공개하지 않는다. */
public interface MemberJpaRepository extends JpaRepository<Member, Long> {
}
