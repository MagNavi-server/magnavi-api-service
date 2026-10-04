# 이 파일은 이미 테스트·빌드한 Spring JAR를 실행 이미지로 포장합니다.
# 메모리가 작은 EC2에서는 Gradle 빌드를 하지 않고, 빌드 환경에서 만든 이미지를 실행합니다.
# Java 21 공식 이미지의 digest(내용 식별값)를 고정해 같은 기반 이미지를 다시 받을 수 있게 합니다.
FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766

# curl은 Compose가 Spring의 준비 상태 HTTP 주소를 호출할 때 사용합니다.
# 설치 목록은 같은 단계에서 지워 이미지에 불필요한 캐시가 남지 않도록 합니다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 magnavi \
    && useradd --uid 10001 --gid magnavi --no-create-home --shell /usr/sbin/nologin magnavi

WORKDIR /app

# 실행 가능한 bootJar 하나만 복사합니다. *-plain.jar는 Spring 실행 파일이 아닙니다.
# 프로젝트 버전이 바뀌면 빌드할 때 --build-arg JAR_FILE=실제경로로 지정합니다.
ARG JAR_FILE=build/libs/MagNavi_SpringServer-0.0.1-SNAPSHOT.jar
COPY --chown=10001:10001 ${JAR_FILE} /app/app.jar

# 애플리케이션은 관리자(root)가 아닌 전용 사용자로 실행합니다.
USER 10001:10001

# 컨테이너 내부에서 사용할 포트 안내입니다. 이 줄만으로 인터넷에 공개되지는 않습니다.
EXPOSE 8080

# 배열 형태로 실행해 종료 신호가 Java에 전달되도록 합니다.
# 비밀번호·JWT 키는 이미지에 넣지 않고 Compose에서 실행 시 주입합니다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
