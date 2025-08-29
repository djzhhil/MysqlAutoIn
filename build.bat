@echo off
REM =====================================================
REM ???? MySQLInstaller ??? EXE?????????
REM =====================================================

REM ------------------ ?? ------------------
set APP_NAME=MysqlAuto
set APP_VERSION=1.0
set TYPE=exe
set INPUT_DIR=target
set MAIN_JAR=MysqlAuto-1.0-SNAPSHOT.jar
set MAIN_CLASS=com.example.mysqlautoin.MySQLInstallerUI
set RUNTIME_IMAGE=target\image
set OUTPUT_DIR=dist

REM ------------------ ?????? ------------------
if not exist %OUTPUT_DIR% mkdir %OUTPUT_DIR%

REM ------------------ ?? jpackage ------------------
jpackage ^
 --name %APP_NAME% ^
 --app-version %APP_VERSION% ^
 --type %TYPE% ^
 --input %INPUT_DIR% ^
 --main-jar %MAIN_JAR% ^
 --main-class %MAIN_CLASS% ^
 --runtime-image %RUNTIME_IMAGE% ^
 --dest %OUTPUT_DIR%

echo.
echo =====================================================
echo ? ???????????? %OUTPUT_DIR%\%APP_NAME%.exe
echo =====================================================
pause
