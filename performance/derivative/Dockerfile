FROM eclipse-temurin:25-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends imagemagick libheif-plugin-libde265 libimage-exiftool-perl python3 ca-certificates && rm -rf /var/lib/apt/lists/*
COPY performance/derivative/command.py /opt/worker/command.py
RUN chmod +x /opt/worker/command.py && ln -s /opt/worker/command.py /opt/worker/convert && ln -s /opt/worker/command.py /opt/worker/exiftool
WORKDIR /workspace
