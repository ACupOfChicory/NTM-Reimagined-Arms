# Перенос сессии: Orange Pi → ПК

Записано 2026-10-23 сессией Claude на Orange Pi для сессии на ПК. Читать целиком перед работой.

## Где что лежит

- Pi в Tailscale: `orangepi4-lts`, `100.82.153.101`, пользователь `acupofchicory`.
- Репозиторий на Pi: `~/Projects/NTM-Reimagined-Arms`, ветка **`tooling`**. Она на 47 коммитов впереди
  `origin/main` и **не запушена** (так задумано: коммитим, не пушим). На GitHub этих изменений нет: забирать с Pi.
- Последний коммит на момент записи: `5e8f6a5` (дальше может быть ещё один, с этим файлом).
- Копии на случай, если git по ssh неудобен (лежат в `build/handoff/`, в git не попадают):
  - `build/handoff/ntm-tooling.bundle`: git bundle ветки `tooling`;
  - `build/handoff/claude-memory.tar.gz`: память Claude с Pi (правила работы, см. ниже).

## Как забрать

Ключа ПК на Pi пока нет: в `~/.ssh/authorized_keys` только ключ телефона. Сначала автор добавляет публичный ключ ПК
в `~/.ssh/authorized_keys` на Pi. Если ssh падает с ошибкой аутентификации, не пробовать снова, а сказать автору.

ssh запускать только так, чтобы он ничего не спросил в терминале (иначе он перехватывает ввод и сессия зависает):

```
timeout 60 ssh -n -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new acupofchicory@100.82.153.101 'cd ~/Projects/NTM-Reimagined-Arms && git log --oneline -1' </dev/null
```

Забрать ветку в свой клон на ПК:

```
GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new' \
  git fetch ssh://acupofchicory@100.82.153.101/home/acupofchicory/Projects/NTM-Reimagined-Arms tooling:tooling
git switch tooling
```

Или через bundle: скопировать `build/handoff/ntm-tooling.bundle` (scp с теми же опциями), затем
`git fetch ntm-tooling.bundle tooling:tooling`.

Сборке нужен `libs/mods/hbm-neoforge-1.0.3.jar` (NTM). В git он не лежит, взять с Pi так же.

## Правила работы (от автора)

- Отвечать **по-русски**, коротко; длинные логи не выводить.
- Новые фичи: сначала предложить (таблицу или план) и ждать ответа. «Что ещё можно» значит предложить, не делать.
- Готовое коммитить логичными коммитами, **без пуша**: `git -c user.name=ACupOfChicory -c
  user.email=acupofchicory@gmail.com commit ...`, в конце `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Каждый найденный баг в `BUGS.md`, исправлен он или нет.
- Заметки и записи вести в репозитории (`review/…`), не в /tmp.
- Мод не выпущен: миграция и совместимость конфигов не нужны.
- «Пробитие» (pierce): сквозь материал, с потерей урона. «Пролёт» (gap): сквозь пустоту, без потерь.
- Файлы с токенами (`.credentials.json` и т. п.) не выводить.
- На Pi железо слабое (3.8 ГБ), поэтому всё дольше минуты запускать в фоне. На ПК это, скорее всего, не нужно,
  но долгие команды в фоне всё равно удобнее.

## Сборка и проверки

- `./gradlew build -PnoAssets`: сборка и тесты. Если изменение задумано, пины обновлять через
  `env PIN_UPDATE=1 ./gradlew build -PnoAssets` (иногда нужно два прохода: `ntmgunfeel-ntm-api.txt` пересобирается
  после проверки пина `resources.txt`).
- `./gradlew runCheckBullets -PnoAssets`: выделенный сервер, реальные пули NTM по реальным блокам. Результат в
  `versions/26.2/build/bulletcheck/result.txt`, сейчас 66 OK.
- `./gradlew runCheckSettings -PnoAssets`: настройки сервера, результат в `versions/26.2/build/settingscheck/result.txt`.
- Что видно только в игре: `review/INGAME-CHECKS.md`.

## Что сделано в последней сессии (2026-10-23)

- `runCheckBullets` (4037943): проверки пуль на сервере без клиента.
- Пересчёт пробития (db7e4cf): толщина пути, мешки и грунт стали `soil`, бетон и сталь держат, дробь пробивает доски.
  Таблица и числа в `Materials.java`, сводка в README («How bullets meet blocks»).
- Деревянные блоки окопов NTM (adb13b4): `wood_barrier`, `wood_structure_ceiling`, `roof` и `scaffold` пуля
  встречает по нарисованным доскам (`WoodBoxes.java`, тест `WoodBoxesTest` против модели NTM). Выход пробитой пули
  из блока с клетками теперь там, где кончаются пиксели.
- Износ с порогом (5e8f6a5): `min=` в правилах `break`; мешки: `break hbm:sandbags hp 600 min=4`. Бетон ломается
  только с `bulletBreakConcrete = true` в `ntm-gunfeel-server.properties` (по умолчанию `false`).

## Открыто

- `BUGS.md` → Open: второй столб или доска в том же блоке не снимает урон.
- Проверить в игре пункты из `review/INGAME-CHECKS.md` (раздел 5 про материалы, новые пункты про окопы и мешки).
- `TODO.md`: ослабление блока вокруг попаданий, пули в воде (отложено автором).
- `ROADMAP.md`: общий план.
- Скриншот с телефона: автор просил посмотреть последний скрин через ssh в Termux (`u0_a324@100.69.156.34`, порт
  8022). Ключ Pi туда не добавлен, задача повисла; с ПК нужен уже ключ ПК.
