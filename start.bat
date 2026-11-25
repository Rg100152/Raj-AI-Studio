@echo off
REM Raj AI Studio — one-command Windows launcher (Python + Java full-stack mode)
setlocal
cd /d "%~dp0"

echo ==============================================================
echo   Raj AI Studio — Full-Stack Launcher
echo   Engineered by Raj Gautam (@linux.userraj)
echo ==============================================================

where python >nul 2>nul || (echo Python not found. Install Python 3.8+. & exit /b 1)
where javac >nul 2>nul || (echo javac not found. Install JDK 17+. & exit /b 1)
where java >nul 2>nul || (echo java not found. Install JDK 17+. & exit /b 1)

echo [1/4] Installing Python dependencies...
python -m pip install -r ai-service-python\requirements.txt

echo [2/4] Starting Python AI microservice (port 5000)...
start "Raj AI Python" cmd /k "cd /d %~dp0ai-service-python && python ai_engine.py"

timeout /t 2 /nobreak >nul

echo [3/4] Compiling Java gateway...
if not exist backend-java\out mkdir backend-java\out
javac -d backend-java\out backend-java\RajGatewayServer.java
if errorlevel 1 (echo Java compilation failed. & exit /b 1)

echo [4/4] Starting Java gateway (port 8080)...
start "Raj AI Java" cmd /k "cd /d %~dp0 && java -cp backend-java\out RajGatewayServer"

echo --------------------------------------------------------------
echo  Full-stack mode ready!
echo  Open: http://localhost:8080
echo  Close the opened windows to stop services.
echo --------------------------------------------------------------
endlocal
