#!/bin/bash

# Ensure script stops on first error
set -e

# Extract current version from pom.xml
CURRENT_VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
BASE_VERSION=${CURRENT_VERSION%-SNAPSHOT}

# Format baseline for suggestions
if [[ $BASE_VERSION =~ ^[0-9]+\.[0-9]+$ ]]; then
  SUGGESTED_RELEASE="${BASE_VERSION}.0"
else
  SUGGESTED_RELEASE="$BASE_VERSION"
fi

# Calculate next minor version
MAJOR=$(echo "$SUGGESTED_RELEASE" | cut -d. -f1)
MINOR=$(echo "$SUGGESTED_RELEASE" | cut -d. -f2)
NEXT_MINOR=$((MINOR + 1))
SUGGESTED_NEXT="${MAJOR}.${NEXT_MINOR}-SNAPSHOT"

# Prompt for the new release version
read -p "Enter the release version [$SUGGESTED_RELEASE]: " RELEASE_VERSION
RELEASE_VERSION=${RELEASE_VERSION:-$SUGGESTED_RELEASE}
if [ -z "$RELEASE_VERSION" ]; then
    echo "Error: Release version cannot be empty."
    exit 1
fi

# Prompt for the next development snapshot version
read -p "Enter the next snapshot version [$SUGGESTED_NEXT]: " NEXT_VERSION
NEXT_VERSION=${NEXT_VERSION:-$SUGGESTED_NEXT}
if [ -z "$NEXT_VERSION" ]; then
    echo "Error: Next version cannot be empty."
    exit 1
fi

echo "=========================================="
echo "Starting release process for v$RELEASE_VERSION"
echo "=========================================="

# 1. Update POM and README.md to the release version
echo "-> Bumping pom.xml and README.md to $RELEASE_VERSION..."
./mvnw versions:set -DnewVersion=$RELEASE_VERSION -q
./mvnw versions:commit -q
sed -i '' -E "s/macfotocli-[a-zA-Z0-9.-]+-jar-with-dependencies\.jar/macfotocli-${RELEASE_VERSION}-jar-with-dependencies.jar/g" README.md

# 2. Commit the release version
echo "-> Committing release version..."
git add pom.xml README.md
git commit -m "chore: release v$RELEASE_VERSION"

# 3. Create the git tag
echo "-> Creating git tag v$RELEASE_VERSION..."
git tag -a "v$RELEASE_VERSION" -m "Release v$RELEASE_VERSION"

# 4. Update POM to the next development snapshot
echo "-> Bumping pom.xml to $NEXT_VERSION for next development cycle..."
./mvnw versions:set -DnewVersion=$NEXT_VERSION -q
./mvnw versions:commit -q

# 5. Commit the snapshot version
echo "-> Committing next development version..."
git add pom.xml README.md
git commit -m "chore: prepare for next development iteration ($NEXT_VERSION)"

echo "=========================================="
echo "✅ Release successfully created!"
echo ""
echo "To push the commits and the new tag to GitHub, run:"
echo "  git push --follow-tags"
echo "=========================================="
