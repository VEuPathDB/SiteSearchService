# # # # # # # # # # # # # # # # # # # # # # # # # # # # # #
#
#   Build Service & Dependencies
#
# # # # # # # # # # # # # # # # # # # # # # # # # # # # # #
FROM amazoncorretto:11-alpine3.16 AS prep

LABEL service="site-search-build"

ARG GITHUB_USERNAME
ARG GITHUB_TOKEN

WORKDIR /workspace

RUN jlink --compress=2 --module-path /opt/jdk/jmods \
       --add-modules java.base,java.net.http,java.security.jgss,java.logging,java.xml,java.desktop,java.management,java.sql,java.naming \
       --output /jlinked \
    && apk add --no-cache git sed findutils coreutils make npm curl bash gawk maven jq \
    && git config --global advice.detachedHead false

ENV DOCKER=build

COPY . .

RUN make jar

# # # # # # # # # # # # # # # # # # # # # # # # # # # # # #
#
#   Run the service
#
# # # # # # # # # # # # # # # # # # # # # # # # # # # # # #
FROM alpine:3.16

LABEL service="site-search"

RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/America/New_York /etc/localtime \
    && echo "America/New_York" > /etc/timezone

ENV JAVA_HOME=/opt/jdk \
    PATH=/opt/jdk/bin:$PATH \
    JVM_ARGS=""

COPY --from=prep /jlinked /opt/jdk
COPY --from=prep /workspace/target/service.jar /service.jar

EXPOSE 8080

CMD java -jar -XX:+CrashOnOutOfMemoryError $JVM_ARGS /service.jar
