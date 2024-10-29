FROM maven:3-ibm-semeru-21-jammy AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn clean package

FROM sonicorg/sonic-agent-linux-base:v1.0.2

WORKDIR /root
COPY docker/mini mini
COPY docker/plugins plugins

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

COPY --from=build /app/target/*.jar app.jar

ENTRYPOINT ["/root/jdk-17+20/bin/java","-server","-Dfile.encoding=utf-8","-XX:-UseGCOverheadLimit","-XX:+DisableExplicitGC","-XX:SurvivorRatio=1","-XX:LargePageSizeInBytes=128M","-XX:SoftRefLRUPolicyMSPerMB=0","-Djava.security.egd=file:/dev/./urandom","--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED","-jar","app.jar"]
