#!/bin/bash

# This script generates the bot's launch script during build with Maven, grabbing all dependencies and putting it
# in the launch command for Java.
# ... yes, this is a Bash script generating a Bash script.

set -xeo pipefail

mv dependency/ java-libs/

echo "#!/bin/bash" > run_bot.sh
echo "" >> run_bot.sh

echo -n "java -Xmx128m -cp \"/app/random-stuff-backend-0.0.1-SNAPSHOT.jar:/app/java-libs/" >> run_bot.sh
echo -n `ls -1 java-libs/ | tr '\n' ':' | sed 's,:,:/app/java-libs/,g' | sed 's,:/app/java-libs/$,,'` >> run_bot.sh
echo "\" ovh.maddie480.randomstuff.backend.CrontabRunner /logs" >> run_bot.sh
