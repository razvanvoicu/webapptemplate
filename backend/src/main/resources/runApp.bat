@echo off
setlocal

set "APP_HOME=%~dp0"

for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%APP_HOME%prod.env") do (
  set "%%A=%%B"
)

java -Djava.net.preferIPv4Stack=true -cp "%APP_HOME%app.jar;%APP_HOME%lib\*" sgrv.be.Main %*
exit /b %ERRORLEVEL%
