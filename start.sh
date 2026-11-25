#!/usr/bin/env bash
# Raj AI Studio — one-command Linux launcher (Python + Java full-stack mode)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
echo -e "${CYAN}==============================================================${NC}"
echo -e "${CYAN}  Raj AI Studio — Full-Stack Launcher${NC}"
echo -e "${CYAN}  Engineered by Raj Gautam (@linux.userraj)${NC}"
echo -e "${CYAN}==============================================================${NC}"

cleanup() {
  echo -e "\n${YELLOW}Shutting down services...${NC}"
  [[ -n "${PY_PID:-}" ]] && kill "$PY_PID" 2>/dev/null || true
  [[ -n "${JAVA_PID:-}" ]] && kill "$JAVA_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

command -v python3 >/dev/null 2>&1 || { echo -e "${RED}python3 not found. Install Python 3.8+.${NC}"; exit 1; }
command -v javac >/dev/null 2>&1 || { echo -e "${RED}javac not found. Install JDK 17+.${NC}"; exit 1; }
command -v java >/dev/null 2>&1 || { echo -e "${RED}java not found. Install JDK 17+.${NC}"; exit 1; }

echo -e "${GREEN}[1/4] Installing Python dependencies...${NC}"
python3 -m pip install --user -r ai-service-python/requirements.txt >/dev/null

echo -e "${GREEN}[2/4] Starting Python AI microservice (port 5000)...${NC}"
(
  cd ai-service-python
  python3 ai_engine.py
) &
PY_PID=$!
sleep 2

echo -e "${GREEN}[3/4] Compiling Java gateway...${NC}"
mkdir -p backend-java/out
javac -d backend-java/out backend-java/RajGatewayServer.java

echo -e "${GREEN}[4/4] Starting Java gateway (port 8080)...${NC}"
(
  java -cp backend-java/out RajGatewayServer
) &
JAVA_PID=$!

echo -e "${CYAN}--------------------------------------------------------------${NC}"
echo -e "${GREEN}✅ Full-stack mode ready!${NC}"
echo -e "   Open: ${CYAN}http://localhost:8080${NC}"
echo -e "   Python API: http://127.0.0.1:5000"
echo -e "   Press Ctrl+C to stop both services."
echo -e "${CYAN}--------------------------------------------------------------${NC}"

wait
