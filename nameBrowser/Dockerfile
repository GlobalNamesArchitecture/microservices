FROM ubuntu:16.04
MAINTAINER Dmitry Mozzherin
ENV LAST_FULL_REBUILD 2017-06-13
ARG VERSION="0.1.0-SNAPSHOT"

RUN apt-get update \
    && apt-get install -y apt-transport-https apt-utils software-properties-common \
    && apt-add-repository ppa:openjdk-r/ppa \
    && apt-get update \
    && apt-get install -y openjdk-8-jre locales curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

RUN mkdir /app
WORKDIR /app

ENV ASSEMBLY_JAR="gnnamebrowser-${VERSION}.jar"
ARG ASSEMBLY_PATH="https://github.com/GlobalNamesArchitecture/gnindex/releases/download/snapshot/${ASSEMBLY_JAR}"
ADD ${ASSEMBLY_PATH} /app/

ADD src/main/resources/names-browse-index.txt /app/names-browse-index.txt

CMD bash -c "java -cp '.' -server -Xms8g -Xmx16g -XX:PermSize=512m \
                  -XX:MaxPermSize=512m -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled \
                  -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70 \
                  -XX:+ScavengeBeforeFullGC -XX:+CMSScavengeBeforeRemark \
                  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dump-$(date +"%Y-%m-%d").hprof \
                  -jar ${ASSEMBLY_JAR} \
                      -index-path=/app/names-browse-index.txt"
