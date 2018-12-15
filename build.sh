#!/bin/bash
echo $(pwd)
echo '*****************'
ls 
docker run -v $(pwd):/home/yaacc/yaacc-code tobexyz/yaacc-ci:0.0.1 bash -c 'cd /home/yaacc/yaacc-code/ && ./gradlew build'

