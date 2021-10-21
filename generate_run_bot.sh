#!/bin/bash

# This script generates the bot's launch script during build with Maven, grabbing all dependencies and putting it
# in the launch command for Java.
# ... yes, this is a Bash script generating a Bash script.

set -xeo pipefail

mv dependency/ java-libs/

echo "#!/bin/bash" > run_bot.sh
echo "" >> run_bot.sh

echo -n "java -Xmx96m -cp \"random-discord-bots-0.0.1-SNAPSHOT.jar:java-libs/" >> run_bot.sh
echo -n `ls -1 java-libs/ | tr '\n' ':' | sed 's,:,:java-libs/,g' | sed 's,:java-libs/$,,'` >> run_bot.sh
echo "\" com.max480.quest.modmanagerbot.Entrypoint /tmp" >> run_bot.sh
