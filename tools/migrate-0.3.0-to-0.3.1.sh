#!/usr/bin/env bash

set -euo pipefail

package_name="com.sergey.animevault"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
apk_path="${1:-$script_dir/AnimeVault-0.3.1-debug.apk}"
backup_path="$script_dir/animevault-0.3.0-backup.tar"

fail() {
    printf 'Ошибка: %s\n' "$1" >&2
    exit 1
}

command -v adb >/dev/null 2>&1 || fail "adb не найден. Установите Android Platform Tools."
command -v tar >/dev/null 2>&1 || fail "tar не найден."
test -f "$apk_path" || fail "APK не найден: $apk_path"
test ! -e "$backup_path" || fail "Файл резервной копии уже существует: $backup_path"

device_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
test "$device_count" -eq 1 || fail "Подключите ровно одно разблокированное устройство с USB-отладкой."
device_serial="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
adb_device=(adb -s "$device_serial")

installed_version="$(
    "${adb_device[@]}" shell dumpsys package "$package_name" \
        | tr -d '\r' \
        | sed -n 's/^[[:space:]]*versionName=//p'
)"
test "$installed_version" = "0.3.0" || fail "Ожидалась установленная версия 0.3.0, найдена: ${installed_version:-не найдена}."

"${adb_device[@]}" shell run-as "$package_name" pwd >/dev/null 2>&1 \
    || fail "run-as недоступен. Установленная 0.3.0 должна быть debug-сборкой."

printf 'Останавливаю AnimeVault 0.3.0 и создаю резервную копию...\n'
"${adb_device[@]}" shell am force-stop "$package_name"
"${adb_device[@]}" exec-out run-as "$package_name" sh -c \
    'cd /data/data/com.sergey.animevault && tar -cf - databases shared_prefs' \
    > "$backup_path"

tar -tf "$backup_path" >/dev/null 2>&1 \
    || fail "Полученный файл не является корректным tar-архивом. 0.3.0 не удалена."
tar -tf "$backup_path" | grep -Fx 'databases/anime_vault.db' >/dev/null \
    || fail "В копии нет databases/anime_vault.db. 0.3.0 не удалена."

printf '\nРезервная копия готова: %s\n' "$backup_path"
printf 'Дальше 0.3.0 будет удалена. Для подтверждения введите MIGRATE: '
read -r confirmation
test "$confirmation" = "MIGRATE" || fail "Операция отменена; установленная 0.3.0 не изменена."

"${adb_device[@]}" uninstall "$package_name"
if ! "${adb_device[@]}" install "$apk_path"; then
    fail "0.3.1 не установилась. Копия сохранена в $backup_path; не удаляйте её."
fi

remote_backup="/data/local/tmp/animevault-0.3.0-backup.tar"
"${adb_device[@]}" push "$backup_path" "$remote_backup" >/dev/null
"${adb_device[@]}" shell chmod 0644 "$remote_backup"
if ! "${adb_device[@]}" shell run-as "$package_name" sh -c \
    "cd /data/data/$package_name && tar -xf $remote_backup && rm -f shared_prefs/online_secure_sessions.xml"; then
    fail "0.3.1 установлена, но восстановление не завершилось. Копия сохранена: $backup_path"
fi
"${adb_device[@]}" shell rm -f "$remote_backup"
"${adb_device[@]}" shell am force-stop "$package_name"

printf '\nГотово. Откройте AnimeVault 0.3.1 и заново добавьте папки, чтобы вернуть SAF-разрешения.\n'
printf 'Токен AnimeLib нужно ввести повторно. Не удаляйте %s до проверки прогресса.\n' "$backup_path"
