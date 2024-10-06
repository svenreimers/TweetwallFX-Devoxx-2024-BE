#!/bin/bash -eu

# Check presence of type value
type=${1:?type was not provided}

# Ensure (for the purposes of this script) that LANG is set to en_US
export LANG=en_US.UTF-8

# Set Java Platform to 23
export JAVA_PLATFORM_VERSION=23

# Update the git repos
echo "# Pull the latest changes for TweetwallFX"
test -d ../TweetwallFX \
&& (cd ../TweetwallFX \
&& git branch && git pull --all --prune)

echo "# Pull the latest changes for TweetwallFX-Devoxx-2024-BE"
git branch \
&& git pull --all --prune

# Run the chosen Tweetwall (based on type)
runTask=$(echo "run_${type}" | awk -F _ '{printf "%s", $1; for(i=2; i<=NF; i++) printf "%s", toupper(substr($i,1,1)) substr($i,2); print"";}')
./gradlew ${runTask}
