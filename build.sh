#!/bin/bash
echo $(pwd)
docker run -v $(pwd):/home/yaacc/yaacc-code tobexyz/yaacc-ci:latest bash -c 'cd /home/yaacc/yaacc-code && ls -la && ./gradlew build'

