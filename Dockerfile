FROM maven:3.9.7-amazoncorretto-17

WORKDIR /app

COPY pom.xml /app
COPY src /app/src

COPY run_test.sh /app

RUN chmod +x /app/run_test.sh

ENTRYPOINT ["/app/run_test.sh"]
