#!/bin/bash
chmod a+w -R $(pwd)
docker run -v $(pwd):/home/yaacc/yaacc-code tobexyz/yaacc-ci:0.0.1 bash -c 'cd /home/yaacc/yaacc-code && ls -la && ./gradlew build'

