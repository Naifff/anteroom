# Контейнер — опция, а не основной путь развёртывания. Основной описан в README:
# systemd-юнит и java -jar. Контейнер прячет каталог data/ за томом, и история
# с резервными копиями перестаёт быть очевидной — а копировать нужно именно его.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Сначала обёртка и описание сборки: пока они не менялись, слой с зависимостями
# берётся из кэша и пересборка не лезет в сеть.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
# Своя учётная запись, а не root: у процесса нет причин мочь больше, чем читать свой
# jar и писать в свой каталог данных.
RUN useradd --system --create-home --home-dir /var/lib/messenger messenger
COPY --from=build /src/build/libs/messenger.jar /opt/messenger/messenger.jar

USER messenger
WORKDIR /var/lib/messenger
# Том обязателен: без него ключ сервера, база и блобы исчезают вместе с контейнером,
# а вместе с ключом исчезает и способность сервера доказать, что он тот же самый.
VOLUME ["/var/lib/messenger"]
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/messenger/messenger.jar", \
            "--app.data-dir=/var/lib/messenger"]
