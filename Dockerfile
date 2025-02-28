FROM openjdk:17
EXPOSE 8080
ADD target/*.jar app.jar
ENTRYPOINT ["java", "-Dspring.profiles.active=private", "-jar", "/app.jar"]