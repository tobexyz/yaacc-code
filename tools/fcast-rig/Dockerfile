# Test receiver for the FCast protocol, for YAACC (issue #327). No media is played. See README.md.
FROM python:3.12-slim
RUN useradd --system --no-create-home --uid 10001 rig
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --no-input -r requirements.txt
COPY fcast_rig.py .
USER 10001
EXPOSE 46899/tcp
# mDNS needs the host's network (see docker-compose.yml); the arguments are given by "docker compose run" or the compose file
ENTRYPOINT ["python3", "-I", "fcast_rig.py"]
CMD ["--host", "0.0.0.0", "--port", "46899", "--name", "YAACC test rig"]
