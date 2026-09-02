#!/usr/bin/env bash
#
# Local development bootstrap. See dev-up.ps1 for the Windows equivalent.
#
# Checks prerequisites, makes a JDK 21 available to Maven, starts PostgreSQL and
# generates the sample deck. Deliberately does not start the backend or the
# frontend: those belong in your own terminals where you can read their logs.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools="$repo/tools"
local_data="$repo/local-data"
sample_deck="$local_data/sample-aif-c01.pdf"

step() { printf '\n==> %s\n' "$1"; }
ok()   { printf '    %s\n' "$1"; }

step 'Checking prerequisites'
for tool in docker node npm curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required and was not found on PATH." >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo 'The Docker daemon is not running.' >&2; exit 1; }
ok "docker $(docker --version | sed 's/Docker version //')"
ok "node $(node --version)"

step 'Checking for a JDK 21'
jdk="$(find "$tools" -maxdepth 1 -type d -name 'jdk-21*' 2>/dev/null | head -1 || true)"
if [ -z "$jdk" ]; then
  # The build targets Java 21, but Maven itself can run on any JDK. Rather than
  # requiring a system-wide upgrade, the repository carries its own.
  ok 'No JDK 21 in tools/. Downloading Temurin 21.'
  mkdir -p "$tools"
  case "$(uname -s)" in
    Darwin) os=mac ;;
    *)      os=linux ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) arch=aarch64 ;;
    *)             arch=x64 ;;
  esac
  curl -fsSL -o /tmp/temurin-21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/${os}/${arch}/jdk/hotspot/normal/eclipse"
  tar -xzf /tmp/temurin-21.tar.gz -C "$tools"
  rm -f /tmp/temurin-21.tar.gz
  jdk="$(find "$tools" -maxdepth 1 -type d -name 'jdk-21*' | head -1)"
  [ -n "$jdk" ] || { echo 'Download finished but no jdk-21* directory appeared in tools/.' >&2; exit 1; }
fi
# macOS archives nest the home directory.
[ -d "$jdk/Contents/Home" ] && jdk="$jdk/Contents/Home"
ok "JDK 21 at $jdk"

toolchains="$HOME/.m2/toolchains.xml"
entry="    <toolchain>
      <type>jdk</type>
      <provides>
        <version>21</version>
        <vendor>temurin</vendor>
      </provides>
      <configuration>
        <jdkHome>$jdk</jdkHome>
      </configuration>
    </toolchain>"

if [ ! -f "$toolchains" ]; then
  mkdir -p "$(dirname "$toolchains")"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n%s\n</toolchains>\n' "$entry" > "$toolchains"
  ok "Wrote $toolchains"
elif grep -q '<version>[[:space:]]*21[[:space:]]*</version>' "$toolchains"; then
  ok "$toolchains already declares a JDK 21"
else
  # Never rewrite a file other projects depend on.
  ok "$toolchains exists but declares no JDK 21. Add this inside <toolchains>:"
  printf '%s\n' "$entry"
fi

step 'Starting PostgreSQL on localhost:5434'
( cd "$repo" && docker compose up -d postgres >/dev/null )
for _ in $(seq 1 45); do
  state="$(docker inspect -f '{{.State.Health.Status}}' certcopilot-postgres 2>/dev/null || echo starting)"
  [ "$state" = healthy ] && break
  sleep 2
done
[ "${state:-}" = healthy ] || { echo "PostgreSQL did not become healthy (last state: ${state:-unknown})." >&2; exit 1; }
ok 'PostgreSQL is healthy. Flyway migrates it on backend startup.'

step 'Checking for the sample deck'
if [ -f "$sample_deck" ]; then
  ok "Sample deck already present: $sample_deck"
else
  ok 'Generating a synthetic AIF-C01-shaped deck (title, dense and diagram slides).'
  mkdir -p "$local_data"
  ( cd "$repo/backend" && ./mvnw -q test -Dtest=DumpDeckTest "-Ddump.deck=$sample_deck" )
  ok "Wrote $sample_deck"
fi

cat <<'NEXT'

Ready. Start the two processes in their own terminals:

  cd backend
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

  cd frontend
  npm install
  npm run dev

Then open http://localhost:5173 and register an account.
Upload local-data/sample-aif-c01.pdf when onboarding asks for material.

This build runs on the deterministic fake AI adapter. Lessons and quizzes are
fixture output and say so in the UI; they are not a measure of content quality.

Stop everything with ./scripts/dev-down.sh
NEXT
