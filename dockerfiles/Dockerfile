FROM openjdk:21

WORKDIR /usr/src

ADD target/rtu_cloudrun-1.0-SNAPSHOT-jar-with-dependencies.jar /usr/src
ADD libs/java-function-invoker-1.4.1.jar /usr/src/
# need to copy /Users/kaiacornell/code/deepdyve/dd-production-content-access.json to tempcreds to be "in context"
COPY tempcreds.json /usr/src/credentials/

# Cloud Run provides PORT; default to 8080 for local runs
ENV PORT=8080
ENV FUNCTION_TARGET=com.deepdyve.rtu_cloudrun.RunOnUpload
# cloudevent for cloudEventsFunction; http for HttpFunction
ENV FUNCTION_SIGNATURE_TYPE=cloudevent

# com.google.cloud.functions.invoker.runner.Invoker. include both jars in classpath
CMD ["java", "-Dconfig.file=cloudrun.properties", "-DgcpCredentialsFile=/usr/src/credentials", "-cp", "java-function-invoker-1.4.1.jar:rtu_cloudrun-1.0-SNAPSHOT-jar-with-dependencies.jar",     "com.google.cloud.functions.invoker.runner.Invoker",   "--classpath", "rtu_cloudrun-1.0-SNAPSHOT-jar-with-dependencies.jar", "--target", "com.deepdyve.rtu_cloudrun.RunOnUpload", "--port", "8080"]

