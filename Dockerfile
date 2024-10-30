FROM maven:3-ibm-semeru-21-jammy AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn clean package

# 使用 Ubuntu 作为基础镜像
FROM ubuntu:22.04
# 更新包索引并安装必要的工具
RUN apt-get update && \
    apt-get install -y wget tar && \
    rm -rf /var/lib/apt/lists/*
# 下载并安装 OpenJDK 21
RUN wget https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz && \
    mkdir -p /usr/lib/jvm/jdk-21 &&\
    tar -xvzf jdk-21_linux-x64_bin.tar.gz -C /usr/lib/jvm/jdk-21 --strip-components=1 && \
    rm jdk-21_linux-x64_bin.tar.gz
# 设置 JAVA_HOME 环境变量
ENV JAVA_HOME=/usr/lib/jvm/jdk-21
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /root
COPY docker/mini mini
COPY docker/plugins plugins
RUN chmod -R 777 plugins
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

COPY --from=build /app/target/*.jar app.jar
COPY config config
CMD ["java", "-jar", "app.jar"]
