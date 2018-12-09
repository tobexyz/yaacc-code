#!/bin/bash
docker run -v $(pwd):/home/yaacc/yaacc-code tobexyz/yaacc-ci:0.0.1 'cd /home/yaacc/yaacc-code/ && ./gradlew build'

