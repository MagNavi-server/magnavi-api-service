package com.example.magnavi_springserver.place.application;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.BadCredentialsException;
import com.example.magnavi_springserver.member.application.MemberService;
import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.place.infrastructure.NaverLocalSearchClient;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 입력을 확인한 뒤 호출 한도 안에서 외부 검색을 실행한다. DB 트랜잭션은 열지 않는다. */
@Service
public class PlaceSearchService {

    private final NaverLocalSearchClient client;
    private final PlaceSearchLimiter limiter;
    private final MemberService members;

    /** 외부 통신과 호출 제한을 분리해 검색 흐름을 쉽게 읽고 검증할 수 있게 한다. */
    public PlaceSearchService(NaverLocalSearchClient client, PlaceSearchLimiter limiter, MemberService members) {
        this.client = client;
        this.limiter = limiter;
        this.members = members;
    }

    /** 회원 번호는 JWT에서 받는다. 입력 오류나 설정 누락은 네이버에 요청하지 않는다. */
    public PlaceSearchResult search(AuthenticatedMember member, String query, int display, String sort) {
        String normalizedQuery = validateQuery(query);
        if (display < 1 || display > 5) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "display");
        }
        if (!"random".equals(sort) && !"comment".equals(sort)) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "sort");
        }
        requireActiveMember(member);
        client.requireConfigured();
        limiter.acquire(member.memberId());
        try {
            return client.search(normalizedQuery, display, sort);
        } finally {
            // 네이버 장애로 실패해도 다음 사용자의 검색 자리를 돌려준다.
            limiter.release();
        }
    }

    /** 회원 모듈의 공개 기능으로 현재 회원을 확인한다. 짧은 조회 트랜잭션은 외부 호출 전에 끝난다. */
    private void requireActiveMember(AuthenticatedMember member) {
        try {
            members.getMyProfile(member);
        } catch (MemberException exception) {
            if (exception.getReason() == MemberException.Reason.UNAUTHENTICATED) {
                throw new BadCredentialsException("유효하지 않은 회원입니다.");
            }
            throw exception;
        }
    }

    /** 앞뒤 공백만 정리하고 대소문자·내부 공백은 보존한다. 길이는 유니코드 문자 수로 센다. */
    private String validateQuery(String query) {
        if (query == null || query.length() > 200 || query.codePoints().anyMatch(Character::isISOControl)) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "query");
        }
        String trimmed = query.strip();
        if (trimmed.isBlank() || trimmed.codePointCount(0, trimmed.length()) > 100) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "query");
        }
        return trimmed;
    }
}
