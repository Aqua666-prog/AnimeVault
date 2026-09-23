@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "PACKAGE_NAME=com.sergey.animevault"
set "SCRIPT_DIR=%~dp0"
set "APK_PATH=%~1"
if not defined APK_PATH set "APK_PATH=%SCRIPT_DIR%AnimeVault-0.3.1-debug.apk"
for %%I in ("%APK_PATH%") do set "APK_PATH=%%~fI"
set "BACKUP_PATH=%SCRIPT_DIR%animevault-0.3.0-backup.tar"

where adb >nul 2>nul || goto :no_adb
where tar >nul 2>nul || goto :no_tar
if not exist "%APK_PATH%" goto :no_apk
if exist "%BACKUP_PATH%" goto :backup_exists

set "DEVICE_COUNT=0"
set "DEVICE_SERIAL="
for /f %%C in ('adb devices ^| findstr /R /C:"device$" ^| find /C /V ""') do set "DEVICE_COUNT=%%C"
for /f "tokens=1" %%S in ('adb devices ^| findstr /R /C:"device$"') do set "DEVICE_SERIAL=%%S"
if not "%DEVICE_COUNT%"=="1" goto :bad_devices

set "INSTALLED_VERSION="
for /f "tokens=2 delims==" %%V in ('adb -s "%DEVICE_SERIAL%" shell dumpsys package "%PACKAGE_NAME%" ^| findstr "versionName="') do if not defined INSTALLED_VERSION set "INSTALLED_VERSION=%%V"
if not "%INSTALLED_VERSION%"=="0.3.0" goto :bad_version

adb -s "%DEVICE_SERIAL%" shell run-as "%PACKAGE_NAME%" pwd >nul 2>nul
if errorlevel 1 goto :no_run_as

echo Останавливаю AnimeVault 0.3.0 и создаю резервную копию...
adb -s "%DEVICE_SERIAL%" shell am force-stop "%PACKAGE_NAME%"
adb -s "%DEVICE_SERIAL%" exec-out run-as "%PACKAGE_NAME%" sh -c "cd /data/data/%PACKAGE_NAME% && tar -cf - databases shared_prefs" > "%BACKUP_PATH%"
if errorlevel 1 goto :bad_backup
tar -tf "%BACKUP_PATH%" >nul 2>nul
if errorlevel 1 goto :bad_backup
tar -tf "%BACKUP_PATH%" | findstr /X /C:"databases/anime_vault.db" >nul
if errorlevel 1 goto :bad_backup

echo.
echo Резервная копия готова: %BACKUP_PATH%
set /p "CONFIRMATION=Дальше 0.3.0 будет удалена. Для подтверждения введите MIGRATE: "
if not "%CONFIRMATION%"=="MIGRATE" goto :cancelled

adb -s "%DEVICE_SERIAL%" uninstall "%PACKAGE_NAME%"
if errorlevel 1 goto :uninstall_failed
adb -s "%DEVICE_SERIAL%" install "%APK_PATH%"
if errorlevel 1 goto :install_failed

set "REMOTE_BACKUP=/data/local/tmp/animevault-0.3.0-backup.tar"
adb -s "%DEVICE_SERIAL%" push "%BACKUP_PATH%" "%REMOTE_BACKUP%" >nul
if errorlevel 1 goto :restore_failed
adb -s "%DEVICE_SERIAL%" shell chmod 0644 "%REMOTE_BACKUP%"
adb -s "%DEVICE_SERIAL%" shell run-as "%PACKAGE_NAME%" sh -c "cd /data/data/%PACKAGE_NAME% && tar -xf %REMOTE_BACKUP% && rm -f shared_prefs/online_secure_sessions.xml"
if errorlevel 1 goto :restore_failed
adb -s "%DEVICE_SERIAL%" shell rm -f "%REMOTE_BACKUP%"
adb -s "%DEVICE_SERIAL%" shell am force-stop "%PACKAGE_NAME%"

echo.
echo Готово. Откройте AnimeVault 0.3.1 и заново добавьте папки для возврата SAF-разрешений.
echo Токен AnimeLib нужно ввести повторно.
echo Не удаляйте %BACKUP_PATH% до проверки прогресса.
goto :success

:no_adb
echo Ошибка: adb не найден. Установите Android Platform Tools.
goto :failure
:no_tar
echo Ошибка: tar не найден. В Windows 10/11 он уже входит в систему.
goto :failure
:no_apk
echo Ошибка: APK не найден: %APK_PATH%
goto :failure
:backup_exists
echo Ошибка: резервная копия уже существует: %BACKUP_PATH%
echo Сохраните или переименуйте ее перед повторным запуском.
goto :failure
:bad_devices
echo Ошибка: подключите ровно одно разблокированное устройство с USB-отладкой.
goto :failure
:bad_version
echo Ошибка: ожидалась установленная версия 0.3.0, найдена: %INSTALLED_VERSION%
goto :failure
:no_run_as
echo Ошибка: run-as недоступен. Установленная 0.3.0 должна быть debug-сборкой.
goto :failure
:bad_backup
echo Ошибка: резервная копия не прошла проверку. 0.3.0 не удалена.
goto :failure
:cancelled
echo Операция отменена. Установленная 0.3.0 не изменена.
goto :failure
:uninstall_failed
echo Ошибка: не удалось удалить 0.3.0. Резервная копия сохранена.
goto :failure
:install_failed
echo Ошибка: 0.3.1 не установилась. Резервная копия сохранена: %BACKUP_PATH%
goto :failure
:restore_failed
echo Ошибка: 0.3.1 установлена, но восстановление не завершилось.
echo Резервная копия сохранена: %BACKUP_PATH%
goto :failure

:success
pause
exit /b 0
:failure
pause
exit /b 1
