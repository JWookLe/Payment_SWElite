FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gnupg unzip \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

ENV GRADLE_VERSION=8.10.2
ENV GRADLE_HOME=/opt/gradle
ENV PATH="$GRADLE_HOME/bin:${PATH}"

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "${GRADLE_HOME}" \
    && rm /tmp/gradle.zip

RUN jenkins-plugin-cli --plugins "workflow-aggregator git git-client"

USER jenkins
