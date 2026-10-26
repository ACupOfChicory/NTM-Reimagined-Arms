# Changelog

## X321

- Обводка F3+B получает позу после подготовки моделей текущего кадра.
  У Flywheel используется снимок, действительно применённый к модели; очередь
  больше не переносит её поворот в следующий кадр.
- Положение обводки учитывает интерполяцию самого рендера. Убрано повторное
  смещение относительно сущности, вызывавшее рассогласование при движении.
  Сохранены оранжевый цвет и обычная проверка глубины.
- Клиентский стенд вращает мобов и проверяет, что обводка использует последнюю
  применённую позу. Проверки прозрачности и контуров используют настоящие
  модели и текстуры курицы и летучей мыши.

## X320

- Поверхности ModelPart вырезаются по непрозрачным пикселям текстуры.
  Прозрачная часть ног курицы больше не считается сплошной коробкой.
  Используются фактические вершины модели с её деформацией; результат
  кэшируется и не пересчитывается по пикселям во время анимации.
- Две стороны плоской детали объединяются перед построением поверхности.
  У крыльев, ушей и лап летучей мыши сохраняется внешний контур. Открытые
  поверхности больше не получают выдуманный внутренний объём.

## X319

- Плоские части моделей теперь участвуют в попаданиях и имеют оранжевую
  обводку. Они сохраняются точными поверхностями без придуманной толщины.
  Исправлены обычный захват, Flywheel и нормализация плоских групп OBJ.
- К телу бриза добавлен его отдельный слой вихря. Используются готовые
  анимационные трансформации, без повторного setupAnim. Клиентский стенд
  проверяет реальные плоские части пчелы и летучей мыши и полный вихрь бриза.
- Стенд учитывает столкновение стрелы и при её штатном отражении бризом; ранее
  такое отражение ошибочно считалось промахом. Игровые правила отражения
  не менялись.

## X318

- Стенд получает полный список живых сущностей из реестра игры: в текущем
  наборе 126 типов, 92 ванильных и 34 NTM, включая боссов и сегменты червя.
- Добавлен интерактивный просмотр: стрелки листают и сохраняют оценки, пробел
  откладывает, Backspace отменяет оценку, N открывает заметку, G ищет по имени
  или ID, F6 переключает фильтры. После перезапуска обзор продолжается
  с непроверенных мобов.
- При отметке ошибки сохраняются кадр и данные модели. Оценки и заметки
  записываются сразу; автоматические проверки показаны отдельно от оценки
  человека. Камера подбирается по размеру цели, Home возвращает её к мобу,
  PgUp/PgDn поворачивают цель.

## X317

- Добавлен клиентский стенд runCheckMobs: 12 мобов, настоящие рендеры, передача
  позы на сервер и F3+B. Проверяются видимая цель, сдвиг и поворот вне кадра,
  выступающие части и пустота внутри прежнего AABB+0.3.
- Пересечения сверяются с отдельным перебором треугольников без ModelBox.clip
  и BVH. Контрольные лучи проходят через штатные пути NTM-пули, ванильной
  стрелы и NTM-луча; у скелета заданы независимые точки головы, ноги и щелей.
- Полный прогон выявляет отсутствие точной позы у голубя и киберкраба NTM
  с Crankshaft и завершается ошибкой. Остальные десять целей проходят сценарии.
  Эти два пробела покрытия записаны в BUGS.md и пока не исправлены.

## X316

- Добавлена автоматическая проверка настоящим клиентом с NTM и Crankshaft:
  штатный обработчик выстрела Richter 9 mm AP, сетевой пакет, начальные нулевые
  кадры, отрисовка трассера и дырок, добавление частиц в движок и разбитое
  серверной пулей стекло. Отдельно проверяются 20 TPS, 2 TPS и неподвижность
  игровых часов в паузе. Используется отдельный тестовый мир.
- Чувствительность проверки подтверждена возвратом старого условия удаления:
  сервер разбивает стекло, клиент теряет все три эффекта, задача Gradle падает.
  Исправленное условие сохраняется в выпускаемой сборке.

## X315

- Исправлено преждевременное удаление клиентского выстрела при возрасте
  ровно ноль. Новый выстрел переживает кадры без продвижения игровых часов,
  затем создаёт трассер, частицы и дырки. Серверное движение не менялось.
- Исправлен remap у Shadow в многоклассовом адаптере глифидов. Проверка
  совместимости теперь выявляет эту ошибку до запуска клиента.

## X314

- Время полёта трассера, его рикошета и звука теперь считается по игровым тикам
  с интерполяцией. Ранее использовались системные часы и постоянные 20 тиков/с,
  поэтому при замедлении игры визуальная пуля обгоняла настоящую. Пауза
  и коррекция времени назад не двигают трассер вперёд или обратно.
- Скорость Richter с 9 mm AP проверена настоящим серверным тиком: около 16
  блоков/тик, или 320 блоков/с при 20 TPS. Скорость и баланс не менялись;
  необходимость упреждения движущейся цели сохраняется.

## X313

- Подключён готовый ModelPart из NTM MobModelVisual. Meltdown Elemental
  получает точный хитбокс и без активного луча, когда обычный рендерер
  отключён. Захват выполняется после обновления GPU-дерева и не повторяет
  setupAnim. Тот же адаптер работает для других мобов этого visual.
- Переключение между обычным рендерером и Flywheel больше не оставляет
  элементаля без первого снимка. Добавлены строки mob_model_pose,
  mob_model_incomplete и mob_model_failed.

## X312

- Убран возврат к ванильному хитбоксу из-за возраста снимка. Рендер может
  пропускать сущность, но её последняя допустимая геометрия остаётся общей для
  обводки, поиска цели и расчёта попаданий.
- Корень сохранённой позы отслеживается раз в игровой тик. Обычное движение
  и поворот не сбрасывают модель; смена UUID, размера или позы тела, телепорт
  и удаление сущности очищают кэш. На клиенте сохраняется исходная
  интерполяция, добавляется только последующий поворот корня.
- Пока рендер не дал новый снимок, поза конечностей остаётся последней
  известной. Отдельное от рендера вычисление анимации не добавлено.

## X311

- Убрана отрисовка хитбоксов сквозь модели и препятствия. Возвращены обычная
  проверка глубины и прежняя толщина линий. Яркий оранжевый цвет сохранён.

## X310

- Maskman и другие живые модели MobObjVisual проверяются по поверхности
  HBM-меша, а не по общей коробке каждой группы. Пустота вокруг наклонных
  деталей и оружия больше не становится зоной попадания.
- Геометрия и дерево поиска треугольников кэшируются. Анимация использует
  готовые матрицы частей; сервер получает геометрию один раз, затем только
  идентификаторы и позы. Ванильные кубические модели сохраняют прежний путь.
- Оранжевая обводка показывает рёбра той же поверхности, по которой считаются
  попадания. Диагонали между треугольниками одной плоской грани скрываются.
- ⚠ Ломает совместимость: сетевой протокол 5. Клиент и сервер необходимо
  обновить вместе; версии с протоколом 4 к этой сборке не подключатся.

## X309

- Исправлен захват Maskman с Flywheel: NTM рисует его через MobObjVisual
  с отключённым RenderMaskMan, поэтому обычный захват X307 не вызывался. Теперь
  используются готовые матрицы и видимость частей MobObjVisual, включая
  переключение головы и черепа. Геометрия групп кэшируется один раз.
- В modeldebug добавлены этапы obj_layout, obj_pose и obj_failed для проверки
  именно рабочего пути отрисовки.

## X308

- У точных хитбоксов F3+B убрана ванильная красная полоса на высоте глаз. Синяя
  линия направления взгляда сохраняется.
- Оранжевая обводка стала ярче (`#FFCC55`), её толщина увеличена до 3.5. Линии
  рисуются поверх геометрии, чтобы части не терялись внутри модели
  и за препятствиями.

## X307

- При F3+B коробки частей модели теперь тёплого оранжевого цвета `#FFAA55`.
- Добавлен пассивный захват частей HBM-мешей: Maskman, Tesla/Taint Crab, дрон
  FBI, UFO и обычный рендерер глифидов. Используются матрицы реально рисуемых
  частей; повторного рендера и обхода вершин каждый кадр нет.
- Глифиды с Flywheel, включая ядерного, передают готовые матрицы частей
  с учётом видимой брони. Hunter Chopper подключён через его ModelPart
  и собственное состояние EntityRenderState.
- Попадания и обводка используют одни коробки частей. При наличии позы
  расширение NTM на 0.3 блока не добавляет невидимую зону попадания. Серверная
  проверка охватывает Maskman и части вне его ванильной коробки.

## X306

- Убран дополнительный доворот клиентского хитбокса к игровому тику. Он нарушал
  плавный поворот уже нарисованной модели и при резком развороте временно
  возвращал общую коробку. Серверный доворот сохранён; его исходный угол теперь
  берётся из настоящего состояния отрисовки.
- Портрет игрока в инвентаре больше не попадает в боевые хитбоксы. В README
  добавлена ссылка на существующие сведения об авторе и MIT-лицензию
  Countered's Accurate Hitboxes; они по-прежнему включены внутрь jar.

## X305

- Убран повторный проход рендерера для хитбоксов. При обычной отрисовке поза
  снимается после уже выполненного setupAnim; при Flywheel/Vanillate берутся
  готовые снимки трансформаций и видимости его частей. Мод больше не создаёт
  render state и не вызывает submit или setupAnim для получения хитбокса.
- Локальные хитбоксы обновляются по готовым кадрам, а сетевые позы отправляются
  отдельно, до десяти раз в секунду. Геометрия модели и маски прозрачности
  кэшируются. В `[modeldebug]` добавлены средняя и пиковая цена захвата кадра.
- Полностью прозрачные кубы больше не становятся хитбоксами: исправлена
  невидимая шляпа основной модели жителя. Маска проверяется по UV настоящей
  текстуры, а не по имени части; кэш очищается при перезагрузке ресурсов.
- Адаптер Flywheel проверяется сборкой по локальному Crankshaft, если его jar
  есть в libs/mods. Код Accurate Hitboxes 2.4.2 не заимствован.

## X304

- Исправлен источник позы: в X303 Flywheel обходил также перехват создания
  состояния моба. В журнал попадала только модель игрока при открытии
  инвентаря. Теперь основной поток сам перебирает загруженных живых сущностей
  и запрашивает полное состояние у их рендереров. Захват больше не зависит
  от того, какие ванильные этапы вызывает Flywheel.
- Бюджет съёма применяется до подготовки состояния и прохода рендерера.
  Диагностика показывает источник `loaded_entities`, неподдерживаемые
  рендереры, исключения подготовки и проход без вызова захвата.

## X303

- Исправлен захват хитбоксов при Flywheel/Vanillate: они обходят обычную
  отрисовку моба, поэтому X301–X302 не получали даже модель скелета.
  Завершённые состояния передаются из рабочих потоков в безопасную очередь;
  основной поток снимает позу отдельным проходом рендерера без рисования
  на GPU. Выбор модели, масштаб и анимации берутся из самого рендерера.
- При F3+B точные коробки частей показываются зелёным и заменяют белую общую
  коробку. Если действующей позы нет, остаётся прежняя коробка. Отладочные
  линии глаз и направления взгляда сохраняются. Обводка использует ту же позу
  и проверку актуальности, что расчёт попаданий.
- Добавлена проверка настоящей модели скелета: съём кубов, зазор между ногами,
  скрытая часть и изменение коробки ноги при анимации ходьбы.

## X302

- Расширена диагностика хитбоксов: строки `[modeldebug]` в `latest.log`
  показывают вызовы привязки состояния и съёма модели, причины отказа,
  полученные кубы, отправку и приём позы, её принятие сервером и переход
  к обычному хитбоксу. Раз в пять секунд выводятся счётчики этапов; подробности
  ограничены по этапу и типу сущности. Включать testlog не нужно. Расчёт
  попаданий и отрисовка не изменены.

## X301

- Встроены хитбоксы частей анимированной модели на основе Countered's Accurate
  Hitboxes 2.0.0. Отдельный мод и Architectury не нужны. Уведомление MIT
  включено в jar; лицензия нашего мода остаётся GPL-3.0-or-later.
- Пересечение луча и проверка точки внутри хитбокса живой сущности используют
  части модели. Это общий механизм для наведения под удар мечом, ванильных
  стрел и других снарядов, пуль и лучей NTM; отдельного включения по пушкам
  нет. Поиск целей учитывает части за пределами прежней коробки в ванильных
  запросах снарядов, NTM и наших трассерах. Геометрия движения сохранена.
- Клиент передаёт серверу позы видимых моделей ModelPart в пределах 96 блоков:
  до 10 обновлений в секунду на сущность, до 16 сущностей за два тика. Сервер
  сам рассчитывает попадание, проверяет размер и привязку модели; поза старше
  пяти тиков, телепортация и смена положения тела возвращают обычный хитбокс.
  Для неподдерживаемых способов отрисовки он тоже сохраняется.
- Общий серверный переключатель `modelHitboxes` включён по умолчанию.
- ⚠ Ломает совместимость: сетевой протокол повышен до 4 из-за передачи поз.
  Клиент и сервер должны обновиться вместе.

## X300

- В редакторе O/Щ на вкладках рук появился выбор модели: по скину, обычные или
  skinny. У skinny отдельная геометрия и развёртка текстуры.
- Правки рук и точек хвата skinny сохраняются отдельно. Без своих правок skinny
  использует прежние настройки; переключение обновляет кэш позиций.
- Во вкладке «Отдача» появился множитель «в прицеле»: по умолчанию 0,7. Он
  уменьшает движения пушки, камеры и анимацию отдачи NTM. Значение 1 сохраняет
  прежнюю силу; минимум отдачи камеры от сервера соблюдается.

## X299

- Cargo-лифт и другие многоблочные машины соблюдают выключенный
  bulletPierceMachines независимо от материала части и отдельного правила
  пробития. Проверка типа машины выполняется до расчёта пробития; пролёт
  через настоящую пустоту сохраняется.

## X298

- Игнорирование порога брони теперь расходуется на всём пути пули
  и не восстанавливается на каждом следующем блоке. Исправлено прохождение DU
  из Mr. Deuce через двенадцать стальных панелей. Одинаковый учёт действует
  на сервере и клиенте; при пересчёте попадания в существо откатывается
  и восстанавливается также расход этого запаса.
- Характеристики патрона и числа материалов не менялись. Для DU Mr. Deuce метр
  укреплённого стекла по нормали почти предельный: после него остаётся 1,2375
  урона; путь 1,031 м уже останавливает пулю. Это соответствует действующей
  формуле.

## X297

- Точные поверхности и прозрачность пикселей теперь применяются к неподвижным
  многоблочным машинам, включая солнечный котёл. Поворот формы сохраняет грани
  и маски; попадание ограничено проверяемой клеткой мира. Пули больше не должны
  останавливаться в пустой части занятой клетки без дырки.
- На границе клеток форма проверяется со стороны движения пули. Исправлено
  пропускание верхней половины бункерной двери при выстреле в отрицательном
  направлении и остановка на стыке половин без дырки.

## X296

- Частицы попадания сохраняют появление на входе в хитбокс. Для блочных крошек
  текстура явно задаётся по блоку, в который попала пуля; создание идёт через
  обычный TerrainParticle с прежней физикой. Отсутствие частиц при попадании
  в сами леса оставлено как задумано.

## X295

- Блочные частицы получают координаты блока попадания для выбора текстуры, даже
  если место их появления вынесено из столкновений с соседними лесами.
- В журнал тестирования добавлено событие fx_particles: число частиц,
  переданных движку, исходный блок, текстура и координаты. Прежнее particles
  в fx означает виды из правила, а не созданные частицы.

## X294

- Пролёты через цепи и стальные леса проверяются по поверхности модели
  и прозрачности пикселей, одинаково на клиенте и сервере. Попадание в занятую
  клетку рядом с металлом больше не создаёт ложное пробитие и потерю урона.
  Точные формы создаются при входе в мир; старые формы читаются по-прежнему.
- Убран SNAP: дырка больше не переносится с луча, прошедшего через пустоту,
  на соседний участок модели. Для закрытой детали выход пули определяется
  по обратной грани.

## X293

- Holes on NTM's steel scaffold again: X290's fix of its bullet shape was not enough (the edges of the diagonal braces
  still made the frame a pixel thick too much) and made chains and other planes drawn from both sides a pixel thicker.
  Both fixed; the shapes are made again on the next world join. Where a bullet stops in a pixel a brace only partly
  fills, its hole goes on the nearest drawn part of the scaffold.

## X292

- Recoil is no longer scaled twice: the gun model's kick and the camera's shake were the preset times NTM's recoil
  number for the gun (0.2–1.4), and light guns also played them faster. Now a shot plays its preset as it is (times
  `modelKick` / `cameraShake`); the preset is for the gun's class already. The camera's up and sideways kick still
  follow NTM's degrees.

## X291

- Recoil is now made by a generator: each movement of the gun model (muzzle up, sideways, roll, up, back) and of the
  camera (up, sideways, roll shake) is a push and a damped spring, set by strength, attack, settle, bounce, delay and
  shake rate. Presets per gun class: default, pistol, revolver, smg, assault, rifle, lever, shotgun, sniper, mg,
  launcher (`kickPreset`, `cameraPreset`; values from real-world recoil data). NTM's guns get their class's preset.
- The editor's "Отдача" tab has a second page "Кривые": the gun's and the camera's preset, the six numbers of each
  movement with a live graph, and "Сохранить пресет" (to config/ntm-gunfeel-recoil.properties).
- The old built-in curves and Henry's and Lincoln's own curves are gone. A curve of your own ("t:v t:v ...") in a
  kick*/camera* key still works.
- ⚠ Ломает совместимость: an empty kick*/camera* key now means the preset's movement, not the old built-in curve.

## X290

- Bullet shapes made from block models are no longer a pixel too fat: a face lying on a pixel border (NTM steel
  scaffold poles at 14/16) went into the empty cell before it. Bullets stopped in air beside a scaffold pole and
  left no hole. The shapes in the jar are made again after the next world join.

## X289

- Arms are drawn whole, like TaCZ: no longer cut at a plane in front of the eye, so a hand near the screen is no
  longer a tiny stub. The editor's "докуда рука" slider is gone (it only cut the arm).

## X288

- The ammo lock key does nothing on what has no magazine (NTM's power armour melee controller): it said "Ammo
  locked: ?" there. X286 undone: our arms were never drawn on that controller.

## X285

- Laminate (block and pane): a hole shows from the other side, through the glass where it was made. Laminate has no
  clear pixels, so its near face hid the hole; holes on such glass are now also drawn under it.

## X284

- Glass holes are back to how they were in X279 and before: X280–X283 undone, and no second mark on the far side of
  stopped-in glass. The one change: NTM's laminate (block and pane) now counts as glass, so its holes follow the
  hitbox like reinforced glass and show from the other side.

## X283

- Glass (reinforced glass, laminate, panes): holes are drawn through the glass where they are, from any side, the edges
  too; no second mark, nothing moves. Holes on glass now lie under the glass in front of them (seen through it) and
  over the glass behind them.

## X282

- Glass (reinforced glass, laminate): from the other side a mark is seen through the glass where it was made, not on
  the near face of the glass.

## X281

- Laminate: a stopped bullet's mark shows from the other side again (X280 did it only for reinforced glass).

## X280

- A bullet stopped in translucent glass (reinforced glass, laminate) leaves one mark, not two: seen from the other
  side the same mark shows right behind where it was hit. Before, a second mark was made where the bullet would have
  come out, so the glass looked pierced.

## X279

- Bullets no longer stop in the air around NTM's solar boiler: the nearly invisible beams from its mirrors
  counted as solid. The boiler's cells are read again when you come near it.

## X278

- Gun editor: with an NTM gun in hand press O (Controls, "NTM Reimagined Arms") for sliders over the game, which
  goes on behind them. Tabs: right hand, left hand (where it holds the gun, the arm's direction, reach, roll,
  thickness), arms (both), recoil (camera and gun model), position (hip fire, tilt, smoke). Changes show at once and
  are saved for this gun only, in `config/ntm-gunfeel-guns.properties`, when you let go of a slider. The wheel moves
  a slider one step (Shift: a tenth), ↺ takes a setting back to the mod's, ● marks the gun's own settings,
  "Отменить всё" goes back to how it was when the editor opened. "Выстрел" and "Очередь" replay the gun's last real
  kick (camera and model; NTM's own recoil animation does not play).

## X277

- A bullet that hits a creature and then goes on into blocks behind it in the same tick (a shot down through a mob
  into the ground) no longer hits the creature with the damage left after those blocks: an armour-piercing .50 into
  a golem's head on grass did 6 instead of about 52.

## X276

- `/gunfeel testlog` writes each bullet hit on a creature: the bullet's damage when it got there, whether NTM
  counted it in the head, the creature's health before and after; each shot also notes how worn the gun is.
  `tools/ingame_report.py` lists these hits with what the bullet pierced on its way.

## X273

- Marks are black again: the colouring by the block (X271) is gone, and so is its setting `holeTint`.
- Metal, machines, wood and every block on no list get the soft soot hole instead of the old holes; the old holes
  stay in the mod as the style "old", for a decal rule to pick.

## X271

- Every mark style has 64 variants instead of 8 (one 8 x 8 texture per style); the first holes ("old") still have
  their own 8, repeated.
- Marks take the colour of the block they are on: a hole in copper is copper-brown, the darkest middle stays black.
  New setting `holeTint` (0 = black as before, 1 = full colour, default 0.5).

## X269

- Holes between two blocks a bullet goes straight through are back for every block (a grate against a scaffold,
  glass against glass); they are left out only between two steel panels (steel corner, steel wall).

## X268

- Glass keeps its holes between blocks: a bullet from one glass block into the next (or between glass and another
  block) leaves them on the face between, seen through the glass.

## X267

- A bullet going from one block straight into the next (two stacked steel blocks) no longer leaves holes on the
  face between them: one hole where it goes in, one where it comes out.

## X266

- Steel: armour-piercing rounds go through more (their threshold negation x (1 + 10 x armour piercing)), and no steel
  or metal block is stronger than 1.5. 2 pixels of steel wall: AP and DU .50 through, FMJ not; NTM's bunker door
  (3 pixels, was out of reach of every round): DU just through, the starmetal .50 easily; a block of steel still
  stops everything. Concrete and soft metal pierce as before.

## X265

- `/gunfeel clearholes` removes every hole, scratch and scorch mark.
- Undone: a machine hit's particles fly off the hitbox's face again (off the model's they stuck inside the machine).

## X264

- Every NTM multiblock is met by its model as drawn, not only the cargo door, the elevator and the cyclotron.
- Machines no longer let bullets through: a thin casing let them pass oddly. `bulletPierceMachines = true` in
  `ntm-gunfeel-server.properties` brings it back.
- NTM's chain-link fence and its post: bullets fly through the mesh's gaps and pierce its wire and posts.
- A bullet stopped in a laminate pane: its hole shows from the other side too.
- Muzzle smoke no longer pours without end from a gun in a new world till it fires (NTM keeps the last shot's time
  from the old world).

## X260

- Machines: a hit's sound and particles come with its hole, where the model is met, not where the bullet enters the
  hitbox; a bullet past a machine's model makes neither.
- The cyclotron is met by its model as drawn, not by its hitbox.
- Holes in the slit between a bottom and a top slab are no longer cut to triangles.
- `/gunfeel holedebug` lists each piece of a hole, shown or hidden and by what.

## X258

- Hit sounds fade with distance as they should: before, set nearer for a 16-block fade while they fade over 24, they
  played at a third of their volume or more up to 40 blocks and then stopped short.
- Hole limits: holes on ordinary blocks count by `holeMax` again (all of them went under `holeMaxMachines`).
- `/gunfeel quality` and the hole limits apply at once, not when the next hole is made.

## X254

- `/gunfeel testlog`: a test log of what bullets, holes, effects, settings and recoil do while you play
  (`logs/ntm-gunfeel-test-*.tsv`; an op switches the server's side on too). `tools/ingame_report.py` checks it
  against `review/INGAME-CHECKS.md`.
- ⚠ Ломает совместимость: сетевой протокол 3 (номер выстрела в ShotPayload, TestLogPayload) — клиент и сервер
  должны быть одной сборки.

## X252

- The version is counted from git: X<commits in main> (`252+mc26.2` for the loader); ports on `port/*` branches are
  X<B>.<P> (`252.3+mc1.7.10-forge`). The jar is now `reimagined-arms-X<build>-mc<Minecraft>.jar`. Before this:
  0.1.1 … 0.1.201, then the Pi work (materials, piercing, bullet rules, Gradle) without numbers.
