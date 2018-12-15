#!/bin/bash
echo $(pwd)
echo '*****************'
ls 
docker run -v $(pwd):/home/yaacc/ tobexyz/yaacc-ci:0.0.1 bash -c 'cd /home/yaacc/ && ls  && ./gradlew build'

