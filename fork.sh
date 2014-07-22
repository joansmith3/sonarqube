#!/bin/sh

mvn clean install -DskipTests -Denforcer.skip=true $*

if [[ "$OSTYPE" == "darwin"* ]]; then
  OS='macosx-universal-64'
else
  OS='linux-x86-64'
fi

cd sonar-application/target/
if ! ls sonarqube-*/bin/$OS/sonar.sh &> /dev/null; then
  unzip sonarqube-*.zip
fi
cd sonarqube-*
bin/$OS/sonar.sh stop
killall -9 java
touch logs/application.log
touch logs/search.log
touch logs/sonar.log
bin/$OS/sonar.sh start
tmux new-session "tmux split-window -v 'tail -f logs/sonar.log'; tmux split-window -h 'tail -f logs/search.log'; tail -f logs/application.log"