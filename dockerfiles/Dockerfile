FROM openjdk:21

COPY ./target /usr/src/rtuCloudRun
WORKDIR /usr/src/rtuCloudRun
# need to copy /Users/kaiacornell/code/deepdyve/dd-production-content-access.json to tempcreds to be "in context"
ADD tempcreds.json /usr/src/credentials

CMD ["java", "-Xmx3000m", "-Dconfig.file=cloudrun.properties", "-DgcpCredentialsFile=/usr/src/credentials", "-cp", "rtu_cloudrun-1.0-SNAPSHOT-jar-with-dependencies.jar", "com.deepdyve.rtu_cloudrun.RunOnUpload"]
