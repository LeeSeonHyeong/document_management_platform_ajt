# ===== 1단계: build — 소스를 실행 가능한 jar 로 컴파일 =====
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 소스 전체 복사 (build/, .gradle/ 는 .dockerignore 로 제외됨)
COPY . .

# gradlew 실행 권한 부여 후 부트 jar 빌드
# --no-daemon: 컨테이너는 일회용이라 gradle 데몬을 띄울 이유가 없음
RUN chmod +x gradlew && ./gradlew clean bootJar --no-daemon

# ===== 2단계: runtime — jar 만 담은 가벼운 실행 이미지 =====
FROM eclipse-temurin:21-jre
WORKDIR /app

# Docker healthcheck가 Spring Actuator 응답을 직접 검사할 때 사용한다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 1단계(build) 에서 만든 jar 하나만 가져온다 (JDK·소스·gradle 캐시는 안 넘어옴)
COPY --from=build /app/build/libs/*.jar app.jar

# 컨테이너 안에서 앱이 여는 포트 (application-local.yml 의 server.port)
EXPOSE 8080

# 컨테이너 시작 시 실행할 명령
ENTRYPOINT ["java", "-jar", "app.jar"]
