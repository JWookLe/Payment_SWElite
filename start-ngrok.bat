@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem  Dynamically detect the docker-compose network and expose Jenkins via ngrok
rem ---------------------------------------------------------------------------

set "NGROK_AUTHTOKEN=34M5vY8iv8P7NhWgPmpDOEelB9d_21gVDQm65VosxYPd69bY7"
set "NETWORK_NAME="

for /f "usebackq tokens=*" %%N in (`docker network ls --format "{{.Name}}"`) do (
    echo %%N | findstr /R /C:"^payment[-_].*default$" >nul
    if not errorlevel 1 (
        set "NETWORK_NAME=%%N"
        goto :FOUND_NETWORK
    )
)

:FOUND_NETWORK
if "%NETWORK_NAME%"=="" (
    echo [ERROR] Could not find docker-compose network that matches payment[-_]...default 1>&2
    exit /b 1
)

echo Using docker network: %NETWORK_NAME%
docker run --rm -it ^
  -p 4040:4040 ^
  --network %NETWORK_NAME% ^
  -e NGROK_AUTHTOKEN=%NGROK_AUTHTOKEN% ^
  ngrok/ngrok:latest http pay-jenkins:8080

pause
endlocal
