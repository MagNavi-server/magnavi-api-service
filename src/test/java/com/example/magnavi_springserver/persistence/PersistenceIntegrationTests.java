package com.example.magnavi_springserver.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.domain.FavoriteTargetType;
import com.example.magnavi_springserver.favorite.infrastructure.persistence.FavoriteJpaRepository;
import com.example.magnavi_springserver.member.domain.LocalCredential;
import com.example.magnavi_springserver.member.domain.Member;
import com.example.magnavi_springserver.member.domain.MemberRole;
import com.example.magnavi_springserver.member.domain.MemberStatus;
import com.example.magnavi_springserver.member.domain.SocialAccount;
import com.example.magnavi_springserver.member.domain.SocialProvider;
import com.example.magnavi_springserver.member.infrastructure.persistence.LocalCredentialJpaRepository;
import com.example.magnavi_springserver.member.infrastructure.persistence.MemberJpaRepository;
import com.example.magnavi_springserver.member.infrastructure.persistence.SocialAccountJpaRepository;
import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.domain.ModelLocationMapping;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.ModelLocationMappingJpaRepository;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** Flyway가 만든 실제 MySQL에서 객체 매핑·저장소·짧은 트랜잭션의 원자성을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class PersistenceIntegrationTests {

    @Autowired private MemberJpaRepository members;
    @Autowired private LocalCredentialJpaRepository credentials;
    @Autowired private SocialAccountJpaRepository socialAccounts;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private ModelLocationMappingJpaRepository mappings;
    @Autowired private FavoriteJpaRepository favorites;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 영속성 컨텍스트를 비운 뒤 8개 엔티티의 실제 DB 복원과 관계·유니코드·시각을 확인한다. */
    @Test
    @Transactional
    void roundTripsAllEightEntities() {
        Member member = members.saveAndFlush(new Member("테스트 회원", "member@example.test", "01000000000"));
        credentials.saveAndFlush(new LocalCredential(member.getId(), " local-user ", "synthetic-hash-only"));
        SocialAccount social = socialAccounts.saveAndFlush(new SocialAccount(member.getId(), SocialProvider.GOOGLE, "CaseSensitiveId"));
        Building building = buildings.saveAndFlush(new Building("검증 건물 🏢", "합성 테스트 주소"));
        Floor floor = floors.saveAndFlush(new Floor(building.getId(), -1, "지하 1층"));
        IndoorLocation location = locations.saveAndFlush(new IndoorLocation(floor.getId(), "시험 장소", "합성 설명"));
        ModelLocationMapping mapping = mappings.saveAndFlush(new ModelLocationMapping("test-area", "v1", "Room-A", location.getId()));
        Favorite favorite = favorites.saveAndFlush(Favorite.indoor(member.getId(), location.getId()));
        entityManager.clear();

        Member loaded = members.findById(member.getId()).orElseThrow();
        assertThat(loaded.getRole()).isEqualTo(MemberRole.USER);
        assertThat(loaded.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(loaded.getEmail()).isEqualTo("member@example.test");
        assertThat(loaded.getPhoneNumber()).isEqualTo("01000000000");
        assertThat(loaded.getCreatedAt()).isEqualTo(member.getCreatedAt());
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(loaded.getCreatedAt().getNano() % 1000).isZero();
        assertThat(credentials.findByLoginId(" local-user ").orElseThrow().getPasswordHash()).isEqualTo("synthetic-hash-only");
        assertThat(socialAccounts.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "CaseSensitiveId").orElseThrow().getId()).isEqualTo(social.getId());
        assertThat(buildings.findById(building.getId()).orElseThrow().getName()).isEqualTo("검증 건물 🏢");
        assertThat(floors.findByBuildingIdAndFloorNumber(building.getId(), -1).orElseThrow().getId()).isEqualTo(floor.getId());
        assertThat(locations.findById(location.getId()).orElseThrow().getDescription()).isEqualTo("합성 설명");
        assertThat(locations.findById(location.getId()).orElseThrow().isActive()).isTrue();
        assertThat(mappings.findByModelKeyAndModelVersionAndLocationCode("test-area", "v1", "Room-A").orElseThrow().getId()).isEqualTo(mapping.getId());
        assertThat(favorites.findByIdAndMemberId(favorite.getId(), member.getId()).orElseThrow().getIndoorLocationId()).isEqualTo(location.getId());
    }

    /** 로그인 ID의 공백은 정리하지만 대소문자는 보존해 저장과 조회의 비교 기준을 맞춘다. */
    @Test
    @Transactional
    void distinguishesLoginIdCaseWhileTrimmingOuterWhitespace() {
        Member upper = members.saveAndFlush(new Member("첫 회원", null, null));
        Member lower = members.saveAndFlush(new Member("둘째 회원", null, null));
        credentials.saveAndFlush(new LocalCredential(upper.getId(), " Alice ", "synthetic-hash-only"));
        credentials.saveAndFlush(new LocalCredential(lower.getId(), "alice", "another-synthetic-hash"));
        entityManager.clear();

        assertThat(credentials.findByLoginId("Alice").orElseThrow().getMemberId()).isEqualTo(upper.getId());
        assertThat(credentials.findByLoginId(" alice ").orElseThrow().getMemberId()).isEqualTo(lower.getId());
        assertThat(credentials.findByLoginId("ALICE")).isEmpty();
        assertThat(credentials.findById(upper.getId()).orElseThrow().getLoginId()).isEqualTo("Alice");
    }

    /** 연락처 없이 저장할 수 있고 같은 이메일을 가진 회원도 별개로 유지한다. */
    @Test
    @Transactional
    void permitsOptionalContactsAndDoesNotMergeRepeatedEmail() {
        Member noContact = members.saveAndFlush(new Member("연락처 미제공", null, null));
        Member first = members.saveAndFlush(new Member("첫 회원", "same@example.test", null));
        Member second = members.saveAndFlush(new Member("둘째 회원", "same@example.test", null));
        entityManager.clear();

        Member loaded = members.findById(noContact.getId()).orElseThrow();
        assertThat(loaded.getEmail()).isNull();
        assertThat(loaded.getPhoneNumber()).isNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(members.findById(first.getId()).orElseThrow().getEmail()).isEqualTo("same@example.test");
        assertThat(members.findById(second.getId()).orElseThrow().getEmail()).isEqualTo("same@example.test");
    }

    /** 모델 코드와 제공자 ID는 DB 조회에서도 대소문자를 구분한다. */
    @Test
    @Transactional
    void preservesCaseSensitiveExternalIdentifiers() {
        Member member = members.saveAndFlush(new Member("회원", "case@example.test", "01000000000"));
        socialAccounts.saveAndFlush(new SocialAccount(member.getId(), SocialProvider.KAKAO, "CaseId"));
        IndoorLocation location = createLocation();
        mappings.saveAndFlush(new ModelLocationMapping("Area", "V1", "Code", location.getId()));
        assertThat(socialAccounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "caseid")).isEmpty();
        assertThat(mappings.findByModelKeyAndModelVersionAndLocationCode("area", "V1", "Code")).isEmpty();
        assertThat(mappings.findByModelKeyAndModelVersionAndLocationCode("Area", "v1", "Code")).isEmpty();
        assertThat(mappings.findByModelKeyAndModelVersionAndLocationCode("Area", "V1", "code")).isEmpty();
    }

    /** 일반 로그인 행 없이도 소셜 전용 회원을 저장할 수 있다. 실제 가입 흐름은 다음 단계다. */
    @Test
    @Transactional
    void savesSocialOnlyMemberWithoutInventedPassword() {
        Member member = members.saveAndFlush(new Member("소셜 회원", "social@example.test", "01000000000"));
        socialAccounts.saveAndFlush(new SocialAccount(member.getId(), SocialProvider.GOOGLE, "social-only"));
        assertThat(credentials.findById(member.getId())).isEmpty();
        assertThat(socialAccounts.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "social-only")).isPresent();
    }

    /** 명시적 소유 조건과 동일 시각의 PK 정렬로 타인 조회와 페이지 중복을 방지한다. */
    @Test
    @Transactional
    void scopesAndOrdersFavoritePagesByOwner() {
        Member owner = members.saveAndFlush(new Member("소유자", "owner@example.test", "01000000000"));
        Member other = members.saveAndFlush(new Member("다른 회원", "other@example.test", "01000000000"));
        Favorite first = favorites.saveAndFlush(Favorite.bus(owner.getId(), "test-provider", "test-region", "route-1"));
        Favorite second = favorites.saveAndFlush(Favorite.busStop(owner.getId(), "test-provider", "test-region", "stop-1"));
        favorites.saveAndFlush(Favorite.bus(other.getId(), "test-provider", "test-region", "route-1"));
        jdbc.update("UPDATE favorites SET created_at = '2020-01-01 00:00:00' WHERE member_id = ?", owner.getId());
        entityManager.clear();

        assertThat(favorites.findByIdAndMemberId(first.getId(), other.getId())).isEmpty();
        var pageOne = favorites.findByMemberIdOrderByCreatedAtDescIdDesc(owner.getId(), PageRequest.of(0, 1));
        var pageTwo = favorites.findByMemberIdOrderByCreatedAtDescIdDesc(owner.getId(), PageRequest.of(1, 1));
        assertThat(pageOne.getContent()).extracting(Favorite::getId).containsExactly(second.getId());
        assertThat(pageOne.hasNext()).isTrue();
        assertThat(pageTwo.getContent()).extracting(Favorite::getId).containsExactly(first.getId());
        assertThat(pageTwo.hasNext()).isFalse();
        assertThat(pageOne.getContent().getFirst().getTargetType()).isEqualTo(FavoriteTargetType.BUS_STOP);
        assertThat(pageTwo.getContent().getFirst().getProviderScope()).isEqualTo("test-region");
        assertThat(pageTwo.getContent().getFirst().getExternalId()).isEqualTo("route-1");
    }

    /** 생성 시각은 보존하고 변경 시각은 JPA 변경 감지 시 UTC로 갱신한다. */
    @Test
    @Transactional
    void updatesAuditTimestampWithoutChangingCreationTime() {
        Member member = members.saveAndFlush(new Member("변경 전", "audit@example.test", "01000000000"));
        jdbc.update("UPDATE members SET created_at = '2020-01-01 00:00:00', updated_at = '2020-01-01 00:00:00' WHERE id = ?", member.getId());
        entityManager.clear();
        Member loaded = members.findById(member.getId()).orElseThrow();
        loaded.changeDisplayName("변경 후");
        members.flush();
        entityManager.clear();
        Member updated = members.findById(member.getId()).orElseThrow();
        assertThat(updated.getDisplayName()).isEqualTo("변경 후");
        assertThat(updated.getCreatedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(updated.getUpdatedAt()).isAfter(updated.getCreatedAt());
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, updated_at, UTC_TIMESTAMP(6)) FROM members WHERE id = ?", Long.class, member.getId())).isBetween(-5L, 5L);
    }

    /** 일반 인증 저장이 중복으로 실패하면 같은 트랜잭션에서 먼저 만든 회원도 남지 않는다. */
    @Test
    void rollsBackNewMemberWhenCredentialInsertFails() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String loginId = UUID.randomUUID().toString();
        Long existingId = transaction.execute(status -> {
            Member existing = members.saveAndFlush(new Member("기존 회원", "existing@example.test", "01000000000"));
            credentials.saveAndFlush(new LocalCredential(existing.getId(), loginId, "synthetic-hash-only"));
            return existing.getId();
        });
        long before = members.count();
        try {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                Member candidate = members.saveAndFlush(new Member("롤백 대상", "rollback@example.test", "01000000000"));
                credentials.saveAndFlush(new LocalCredential(candidate.getId(), loginId, "another-synthetic-hash"));
            })).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(members.count()).isEqualTo(before);
            assertThat(credentials.findByLoginId(loginId).orElseThrow().getMemberId()).isEqualTo(existingId);
        } finally {
            transaction.executeWithoutResult(status -> {
                credentials.deleteById(existingId);
                credentials.flush();
                members.deleteById(existingId);
            });
        }
    }

    /** FK를 만족하는 합성 장소를 만들며 실제 시설 데이터는 사용하지 않는다. */
    private IndoorLocation createLocation() {
        Building building = buildings.saveAndFlush(new Building("시험 건물", "합성 주소"));
        Floor floor = floors.saveAndFlush(new Floor(building.getId(), 1, "1층"));
        return locations.saveAndFlush(new IndoorLocation(floor.getId(), "시험 장소", null));
    }
}
